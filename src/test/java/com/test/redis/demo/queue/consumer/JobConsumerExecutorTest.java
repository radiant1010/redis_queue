package com.test.redis.demo.queue.consumer;

import com.test.redis.demo.queue.consumer.executor.JobConsumerExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class JobConsumerExecutorTest {
    @Test
    void springCloseWaitsForConsumerBeforeDestroyingDependencies() throws Exception {
        BlockingConsumer consumer = new BlockingConsumer();
        try (AnnotationConfigApplicationContext context = context(consumer, 5000)) {
            assertThat(consumer.entered.await(3, TimeUnit.SECONDS)).isTrue();
            Thread closer = new Thread(context::close);
            closer.start();
            try {
                assertThat(consumer.stopRequested.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(closer.isAlive()).isTrue();
                assertThat(consumer.interrupted.get()).isFalse();
                assertThat(consumer.destroyed.get()).isFalse();
            } finally {
                consumer.release.countDown();
                closer.join(5000);
            }
            assertThat(closer.isAlive()).isFalse();
            assertThat(consumer.exited.getCount()).isZero();
            assertThat(consumer.destroyed.get()).isTrue();
        }
    }

    @Test
    void springTimeoutInterruptsCooperativeHandler() throws Exception {
        BlockingConsumer consumer = new BlockingConsumer();
        try (AnnotationConfigApplicationContext context = context(consumer, 100)) {
            assertThat(consumer.entered.await(3, TimeUnit.SECONDS)).isTrue();
            context.close();
            assertThat(consumer.exited.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(consumer.interrupted.get()).isTrue();
        } finally {
            consumer.release.countDown();
        }
    }

    @Test
    void everyRegisteredConsumerGetsAThread() throws Exception {
        List<BlockingConsumer> consumers = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> new BlockingConsumer()).toList();
        JobConsumerExecutor executor = new JobConsumerExecutor(List.copyOf(consumers));
        try {
            executor.start();
            executor.start(); // Repeated start must not submit duplicate workers.
            for (BlockingConsumer consumer : consumers) {
                assertThat(consumer.entered.await(3, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            consumers.forEach(consumer -> consumer.release.countDown());
            CountDownLatch stopped = new CountDownLatch(1);
            executor.stop(stopped::countDown);
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
            executor.destroy();
        }
    }

    private AnnotationConfigApplicationContext context(BlockingConsumer consumer, long timeout) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
            DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
            processor.setTimeoutPerShutdownPhase(timeout);
            return processor;
        });
        context.registerBean("consumer", BlockingConsumer.class, () -> consumer,
                definition -> definition.setDestroyMethodName("destroy"));
        context.registerBean(JobConsumerExecutor.class);
        context.refresh();
        return context;
    }

    static class BlockingConsumer implements JobConsumer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopRequested = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean destroyed = new AtomicBoolean();
        public void start() { }
        public void stop() { stopRequested.countDown(); }
        public void consume() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                exited.countDown();
            }
        }
        public void destroy() { destroyed.set(true); }
    }
}
