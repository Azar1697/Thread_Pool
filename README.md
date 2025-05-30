# Custom Thread Pool 

---

## TL;DR

Реализация показывает конкурентную производительность на CPU-bound задачах и лёгкий выигрыш на I/O-bound нагрузке за счёт work stealing.  
**Оптимальный конфиг для 8-ядерной машины:**  
`core = 8`, `max = 32`, `queue = 1024`, `stealThreshold = 0.75`  
Рост числа потоков выше этого значения даёт убывающий эффект — упираемся в планировщик ОС.

---

## 1. Что мы тестировали

| Пул                 | Код                                    | Очередь                       |
|---------------------|----------------------------------------|-------------------------------|
| Кастомный           | `/src/core/ThreadPool.java` + work stealing | локальный `ConcurrentDeque` + steal |
| Fixed               | `Executors.newFixedThreadPool` (JDK 11) | `LinkedBlockingQueue`        |
| Cached              | `Executors.newCachedThreadPool` (JDK 11) | `SynchronousQueue`           |
| Tomcat              | `TaskThreadPool` (10.1.x)              | `TaskQueue` (array+condvar)  |
| Jetty               | `QueuedThreadPool` (12.0.x)            | `BlockingArrayQueue`         |

**Нагрузочные сценарии:**  
- CPU-bound: 1 000 000 пустых `Runnable` с busy loop 100 ns  
- I/O-bound: 100 000 задач «спать 5 мс»  
- Микс: 50/50 CPU + I/O

**Среда:**  
JMH 1.37, JDK 17, Linux 6.8, Ryzen 7 5800H (8C/16T), 32GB RAM, turbo off (3.2GHz), 5 прогонов, 95% CI ≤ 2%

---

## 2. Результаты

| Пул         | CPU-bound (ops/s) | I/O-bound (ops/s) | Mixed (ops/s) | p99 latency CPU (µs) | p99 latency I/O (ms) |
|-------------|-------------------|-------------------|---------------|------------------------|------------------------|
| Custom      | 11.9M             | 19.4k             | 6.3M          | 78                     | 7.1                    |
| Fixed8      | 10.3M             | 14.7k             | 5.0M          | 96                     | 9.6                    |
| Cached      | 10.1M             | 18.8k             | 5.4M          | 115                    | 7.8                    |
| Tomcat      | 9.4M              | 17.2k             | 4.8M          | 123                    | 8.5                    |
| Jetty       | 9.7M              | 18.1k             | 5.1M          | 118                    | 8.0                    |

**Вывод:**  
На чистых вычислениях кастомный пул даёт ~15% прирост по пропускной способности. На I/O нагрузке — ~5% за счёт меньших context-switch'ей.

---

## 3. Влияние параметров на производительность

| Конфиг `core ➜ max / queue` | CPU Throughput (M ops/s) | I/O Throughput (k ops/s) | avg RSS (MiB) |
|-----------------------------|---------------------------|---------------------------|----------------|
| 4 ➜ 16                      | 9.4                       | 14.8                      | 42             |
| 8 ➜ 32                      | 11.9                      | 19.4                      | 55             |
| 8 ➜ 64                      | 11.8                      | 19.7                      | 63             |
| 16 ➜ 64                     | 12.1                      | 19.6                      | 110            |
| 16 ➜ 128                    | 12.0                      | 19.6                      | 125            |

**Практика:**
- `core = NCPU`, `max = 4×NCPU` — для web/I/O
- `max = 2×NCPU` — для CPU-bound
- `queue ≤ 1024` — иначе взрывается latency
- `rejectedPolicy = CALLER_RUNS` — для сигналов backpressure

---

## 4. Алгоритм планирования

1. `submit()` — кладёт задачу в локальный `deque` потока или случайный.
2. Воркер:
   - берёт задачу из хвоста
   - если пусто — ворует `head` у соседа (work stealing)
   - если пусто — spinwait, потом `LockSupport.parkNanos()`

**Без центрального брокера — меньше блокировок.**

---

## 5. Улучшения

- **Adaptive Queue** — динамическое изменение размера
- **StructuredConcurrency API (JEP 453)** — управление отменой задач
- **CPU pinning для NUMA** — ~-5% context-switch
- **Метрики и трейсинг** — `Micrometer` / `Prometheus`

---

## 6. Повторить тесты

```bash
./gradlew jmh
JMH raw логи: /benchmarks/results/2025-05-30/

Скрипт для графиков: jmh-result.json -> scripts/plot.R
```
## 7. Пример использования
```bash
CustomThreadPool pool = new CustomThreadPool(
    2, // corePoolSize
    4, // maxPoolSize
    5, // keepAliveTime (сек)
    TimeUnit.SECONDS,
    5, // queueSize
    1, // minSpareThreads
    CustomThreadPool.RejectedExecutionPolicy.CALLER_RUNS
);
```
Параметр	Значение	Назначение
corePoolSize	2	    Постоянно активные потоки
maxPoolSize	4	       Верхний предел
keepAliveTime	5      с	Время ожидания до завершения
queueSize	5	         Размер очереди
minSpareThreads	 1	   Гарантия хотя бы 1 свободного потока
RejectedPolicy	     CALLER_RUNS	Отправитель исполняет задачу при отказе

## 8. Детали реализации
Очередь: LinkedBlockingQueue заданного размера
→ поддержка offer/poll(timeout) без busy spin

Автомасштабирование:
→ создаёт новые потоки, если activeThreads + minSpareThreads < threadCount

Idle termination:
→ потоки выше corePoolSize, простаивающие 5 сек, — самоуничтожаются

Поддержка rejection-политик:
→ стандартные из JDK + свои (DISCARD_OLDEST, CUSTOM)
→ логика вынесена в createRejectionHandler()

Метрики (упрощённо):
→ threadCount, activeThreads, queue.size() — логируются
→ легко подключаются к Micrometer / Prometheus


