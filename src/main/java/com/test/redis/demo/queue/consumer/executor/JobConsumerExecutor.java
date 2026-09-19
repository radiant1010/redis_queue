package com.test.redis.demo.queue.consumer.executor;

import com.test.redis.demo.queue.consumer.JobConsumer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the consumer threads so Redis remains available until consumers actually exit. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queue.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class JobConsumerExecutor implements SmartLifecycle {
    private final List<JobConsumer> consumers;
    private volatile boolean running;
    private ExecutorService executor;

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (executor != null && !executor.isTerminated()) {
            throw new IllegalStateException("Previous consumers are still stopping");
        }
        AtomicInteger index = new AtomicInteger();
        executor = Executors.newFixedThreadPool(Math.max(1, consumers.size()), task ->
                new Thread(task, "job-consumer-" + index.incrementAndGet()));
        try {
            consumers.forEach(JobConsumer::start);
            running = true;
            consumers.forEach(consumer -> executor.execute(consumer::consume));
        } catch (RuntimeException e) {
            running = false;
            consumers.forEach(JobConsumer::stop);
            executor.shutdownNow();
            throw e;
        }
    }

    @Override
    public synchronized void stop(Runnable callback) {
        running = false;
        consumers.forEach(JobConsumer::stop);
        if (executor == null) {
            callback.run();
            return;
        }
        ExecutorService stoppingExecutor = executor;
        stoppingExecutor.shutdown();
        // Spring's phase timeout bounds graceful shutdown; do not block its lifecycle thread.
        Thread waiter = new Thread(() -> {
            try {
                while (!stoppingExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    // Only report completion after real termination.
                }
                callback.run();
                log.info("All job consumers stopped");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "job-consumer-shutdown");
        waiter.setDaemon(true);
        waiter.start();
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @PreDestroy
    public synchronized void destroy() {
        running = false;
        consumers.forEach(JobConsumer::stop);
        if (executor != null && !executor.isTerminated()) {
            log.warn("Consumer shutdown deadline exceeded; interrupting remaining work");
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
