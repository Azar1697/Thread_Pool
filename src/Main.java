import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        CustomThreadPool pool = new CustomThreadPool(
                2, // corePoolSize
                4, // maxPoolSize
                5, // keepAliveTime
                TimeUnit.SECONDS,
                5, // queueSize
                1, // minSpareThreads
                CustomThreadPool.RejectedExecutionPolicy.CALLER_RUNS
        );

        // Запускаем задачи
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            try {
                pool.execute(() -> {
                    System.out.println("Task " + taskId + " started in " +
                            Thread.currentThread().getName());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("Task " + taskId + " completed");
                });
            } catch (RejectedExecutionException e) {
                System.out.println("Task " + taskId + " rejected");
            }
        }

        Thread.sleep(10000);
        pool.shutdown();
    }
}