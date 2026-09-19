package com.test.redis.demo.queue.consumer.executor;

import com.test.redis.demo.queue.consumer.JobConsumer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Stops workers before Spring advances to Redis's lower shutdown phase. */
@Slf4j
@Component
@ConditionalOnProperty(name = "queue.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class JobConsumerExecutor implements SmartLifecycle {
    private final List<JobConsumer> consumers;
    private final Duration gracePeriod;
    private final Duration interruptWait;
    private volatile boolean running;
    private ExecutorService executor;
    private CompletableFuture<Void> stopCompletion;

    @Autowired
    public JobConsumerExecutor(List<JobConsumer> consumers,
            @Value("${queue.shutdown.grace-period:45s}") String gracePeriod,
            @Value("${queue.shutdown.interrupt-wait:5s}") String interruptWait,
            @Value("${spring.lifecycle.timeout-per-shutdown-phase:60s}") String springTimeout) {
        this(consumers, DurationStyle.detectAndParse(gracePeriod), DurationStyle.detectAndParse(interruptWait));
        if (this.gracePeriod.plus(this.interruptWait).compareTo(DurationStyle.detectAndParse(springTimeout)) >= 0) {
            throw new IllegalArgumentException("Consumer shutdown budget must be shorter than Spring's shutdown phase timeout");
        }
    }

    public JobConsumerExecutor(List<JobConsumer> consumers) {
        this(consumers, Duration.ofSeconds(45), Duration.ofSeconds(5));
    }

    public JobConsumerExecutor(List<JobConsumer> consumers, Duration gracePeriod, Duration interruptWait) {
        if (gracePeriod.isNegative() || interruptWait.isNegative() || interruptWait.isZero()) {
            throw new IllegalArgumentException("Invalid consumer shutdown durations");
        }
        this.consumers = List.copyOf(consumers);
        this.gracePeriod = gracePeriod;
        this.interruptWait = interruptWait;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        if (executor != null && !executor.isTerminated()) {
            throw new IllegalStateException("Previous consumers are still stopping");
        }
        AtomicInteger index = new AtomicInteger();
        executor = Executors.newFixedThreadPool(Math.max(1, consumers.size()), task ->
                new Thread(task, "job-consumer-" + index.incrementAndGet()));
        stopCompletion = null;
        try {
            consumers.forEach(JobConsumer::start);
            running = true;
            consumers.forEach(consumer -> executor.execute(consumer::consume));
        } catch (RuntimeException e) {
            running = false;
            consumers.forEach(JobConsumer::abort);
            executor.shutdownNow();
            throw e;
        }
    }

    @Override
    public synchronized void stop(Runnable callback) {
        running = false;
        if (executor == null || executor.isTerminated()) {
            callback.run();
            return;
        }
        if (stopCompletion != null) {
            stopCompletion.thenRun(callback);
            return;
        }
        consumers.forEach(JobConsumer::stop);
        ExecutorService stoppingExecutor = executor;
        stoppingExecutor.shutdown();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        stopCompletion = completion;
        completion.thenRun(callback);
        Thread waiter = new Thread(() -> awaitShutdown(stoppingExecutor, completion), "job-consumer-shutdown");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void awaitShutdown(ExecutorService stoppingExecutor, CompletableFuture<Void> completion) {
        try {
            if (!stoppingExecutor.awaitTermination(gracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Consumer grace period elapsed; cancelling work before Redis shutdown");
                // Fence late acknowledgements even when a custom handler swallows interruption.
                consumers.forEach(JobConsumer::abort);
                stoppingExecutor.shutdownNow();
                if (!stoppingExecutor.awaitTermination(interruptWait.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.error("Consumer ignored cancellation; Processing jobs remain for recovery. "
                            + "Spring may reach its shutdown deadline before this handler exits.");
                    // Never report a live worker as terminated. Custom handlers must bound I/O and honor cancellation.
                    while (!stoppingExecutor.awaitTermination(1, TimeUnit.SECONDS)) { }
                }
            }
            completion.complete(null);
            log.info("All job consumers stopped");
        } catch (InterruptedException e) {
            consumers.forEach(JobConsumer::abort);
            stoppingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() { stop(() -> { }); }

    @PreDestroy
    public synchronized void destroy() {
        running = false;
        if (executor != null && !executor.isTerminated()) {
            log.warn("Destroying unfinished consumer executor; requesting cancellation");
            consumers.forEach(JobConsumer::abort);
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE; }
}
