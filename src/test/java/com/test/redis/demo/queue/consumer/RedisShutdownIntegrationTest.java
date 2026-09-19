package com.test.redis.demo.queue.consumer;

import com.test.redis.demo.queue.consumer.executor.JobConsumerExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisShutdownIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:6.2.20-alpine").withExposedPorts(6379);

    @Test
    void deadlineInterruptsConsumerWhileRedisFactoryIsStillRunning() throws Exception {
        try (AnnotationConfigApplicationContext context = context("100ms")) {
            ProbeConsumer consumer = context.getBean(ProbeConsumer.class);
            assertThat(consumer.entered.await(3, TimeUnit.SECONDS)).isTrue();
            context.close();
            assertThat(consumer.exited.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(consumer.failure.get()).as("Redis cleanup during consumer exit").isNull();
            assertThat(consumer.redisWasRunning).isTrue();
            assertThat(consumer.interrupted).isTrue();
        }
    }

    @Test
    void normalShutdownKeepsRedisAvailableUntilCurrentWorkFinishes() throws Exception {
        try (AnnotationConfigApplicationContext context = context("1s")) {
            ProbeConsumer consumer = context.getBean(ProbeConsumer.class);
            assertThat(consumer.entered.await(3, TimeUnit.SECONDS)).isTrue();
            Thread closer = new Thread(context::close);
            closer.start();
            try {
                assertThat(consumer.stopRequested.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(context.getBean(LettuceConnectionFactory.class).isRunning()).isTrue();
                consumer.release.countDown();
                closer.join(5000);
                assertThat(closer.isAlive()).isFalse();
                assertThat(consumer.failure.get()).isNull();
                assertThat(consumer.redisWasRunning).isTrue();
            } finally {
                consumer.release.countDown();
                closer.join(5000);
            }
        }
    }

    private AnnotationConfigApplicationContext context(String gracePeriod) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("shutdown-test", Map.of(
                "queue.shutdown.grace-period", gracePeriod, "queue.shutdown.interrupt-wait", "1s",
                "spring.lifecycle.timeout-per-shutdown-phase", "3s")));
        context.registerBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
            DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
            processor.setTimeoutPerShutdownPhase(3000);
            return processor;
        });
        context.registerBean(LettuceConnectionFactory.class,
                () -> new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)));
        context.registerBean(StringRedisTemplate.class,
                () -> new StringRedisTemplate(context.getBean(LettuceConnectionFactory.class)));
        context.registerBean(ProbeConsumer.class);
        context.registerBean(JobConsumerExecutor.class);
        context.refresh();
        return context;
    }

    static class ProbeConsumer implements JobConsumer {
        private final LettuceConnectionFactory factory;
        private final StringRedisTemplate redis;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopRequested = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean redisWasRunning;
        volatile boolean interrupted;

        ProbeConsumer(LettuceConnectionFactory factory, StringRedisTemplate redis) {
            this.factory = factory;
            this.redis = redis;
        }
        public void start() { }
        public void stop() { stopRequested.countDown(); }
        public void consume() {
            entered.countDown();
            try {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                redisWasRunning = factory.isRunning();
                redis.opsForValue().set("shutdown-test", "finished");
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                exited.countDown();
            }
        }
    }
}
