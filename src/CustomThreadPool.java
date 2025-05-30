import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
//
//mvn archetype:generate \ -DgroupId=com.promo \ -DartifactId=otp-backend \ -DarchetypeArtifactId=maven-archetype-quickstart \  -DinteractiveMode=false
//        -DgroupId=com.promo \
//        -DartifactId=otp-backend \
//        -DarchetypeArtifactId=maven-archetype-quickstart \
//        -DinteractiveMode=false

public class CustomThreadPool implements CustomExecutor {
    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int queueSize;
    private final int minSpareThreads;

    private final AtomicInteger threadCount = new AtomicInteger(0);
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private final BlockingQueue<Runnable> mainQueue;
    private final List<WorkerThread> workers = new CopyOnWriteArrayList<>();
    private final RejectedExecutionHandler rejectionHandler;
    private final CustomThreadFactory threadFactory;

    private volatile boolean isShutdown = false;
    private final Lock mainLock = new ReentrantLock();

    public CustomThreadPool(int corePoolSize, int maxPoolSize, long keepAliveTime,
                            TimeUnit timeUnit, int queueSize, int minSpareThreads,
                            RejectedExecutionPolicy policy) {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.queueSize = queueSize;
        this.minSpareThreads = minSpareThreads;
        this.mainQueue = new LinkedBlockingQueue<>(queueSize);
        this.threadFactory = new CustomThreadFactory();
        this.rejectionHandler = createRejectionHandler(policy);

        // Инициализация core потоков
        for (int i = 0; i < corePoolSize; i++) {
            addWorker();
        }
    }

    private RejectedExecutionHandler createRejectionHandler(RejectedExecutionPolicy policy) {
        switch (policy) {
            case ABORT:
                return (r, executor) -> {
                    log("[Rejected] Task " + r + " was rejected due to overload!");
                    throw new RejectedExecutionException("Task " + r + " rejected");
                };
            case CALLER_RUNS:
                return (r, executor) -> {
                    log("[Rejected] Task " + r + " will run in caller thread");
                    r.run();
                };
            case DISCARD:
                return (r, executor) -> log("[Rejected] Task " + r + " was discarded");
            case DISCARD_OLDEST:
                return (r, executor) -> {
                    Runnable old = mainQueue.poll();
                    log("[Rejected] Oldest task " + old + " discarded");
                    execute(r);
                };
            default:
                return (r, executor) -> {
                    log("[Rejected] Task " + r + " was rejected (custom policy)");
                    throw new RejectedExecutionException("Task " + r + " rejected");
                };
        }
    }

    private void addWorker() {
        if (threadCount.get() >= maxPoolSize) return;

        WorkerThread worker = new WorkerThread(threadFactory.newThread(() -> {}));
        worker.start();
        workers.add(worker);
        threadCount.incrementAndGet();
    }

    @Override
    public void execute(Runnable command) {
        if (isShutdown) {
            rejectionHandler.rejectedExecution(command, null);
            return;
        }

        // Проверяем минимальное количество резервных потоков
        if (threadCount.get() - activeThreads.get() < minSpareThreads
                && threadCount.get() < maxPoolSize) {
            addWorker();
        }

        try {
            if (!mainQueue.offer(command, 100, TimeUnit.MILLISECONDS)) {
                rejectionHandler.rejectedExecution(command, null);
            } else {
                log("[Pool] Task accepted into queue: " + command);

                // Если есть свободные потоки, но задачи в очереди
                if (activeThreads.get() < threadCount.get() && !mainQueue.isEmpty()) {
                    signalIdleWorker();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectionHandler.rejectedExecution(command, null);
        }
    }

    @Override
    public <T> Future<T> submit(Callable<T> callable) {
        FutureTask<T> future = new FutureTask<>(callable);
        execute(future);
        return future;
    }

    @Override
    public void shutdown() {
        isShutdown = true;
        log("[Pool] Shutdown initiated");
    }

    @Override
    public void shutdownNow() {
        isShutdown = true;
        mainQueue.clear();
        for (WorkerThread worker : workers) {
            worker.interrupt();
        }
        log("[Pool] Shutdown now initiated");
    }

    private void signalIdleWorker() {
        for (WorkerThread worker : workers) {
            if (worker.isWaiting()) {
                worker.interrupt();
                break;
            }
        }
    }

    private class WorkerThread extends Thread {
        private volatile boolean running = true;
        private volatile boolean waiting = false;

        public WorkerThread(Thread thread) {
            super(thread, "CustomPool-worker-" + threadCount.get());
        }

        public boolean isWaiting() {
            return waiting;
        }

        @Override
        public void run() {
            Runnable task;

            while (running && !isShutdown) {
                try {
                    activeThreads.incrementAndGet();
                    task = mainQueue.poll(keepAliveTime, timeUnit);

                    if (task != null) {
                        waiting = false;
                        log("[Worker] " + getName() + " executes " + task);
                        task.run();
                    } else {
                        waiting = true;
                        // Проверяем нужно ли завершать поток
                        if (threadCount.get() > corePoolSize) {
                            log("[Worker] " + getName() + " idle timeout, stopping");
                            running = false;
                            threadCount.decrementAndGet();
                            workers.remove(this);
                        }
                    }
                } catch (InterruptedException e) {
                    // Прерывание для обработки новой задачи
                    waiting = false;
                } finally {
                    activeThreads.decrementAndGet();
                }
            }

            log("[Worker] " + getName() + " terminated");
        }
    }

    private static class CustomThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "CustomPool-worker-" + counter.incrementAndGet());
            log("[ThreadFactory] Creating new thread: " + thread.getName());
            return thread;
        }
    }

    private static void log(String message) {
        System.out.println(Thread.currentThread().getName() + " | " +
                System.currentTimeMillis() + " | " + message);
    }

    public enum RejectedExecutionPolicy {
        ABORT, CALLER_RUNS, DISCARD, DISCARD_OLDEST, CUSTOM
    }
}

interface CustomExecutor extends Executor {
    void execute(Runnable command);
    <T> Future<T> submit(Callable<T> callable);
    void shutdown();
    void shutdownNow();
}