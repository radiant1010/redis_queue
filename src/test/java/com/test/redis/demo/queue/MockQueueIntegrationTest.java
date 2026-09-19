package com.test.redis.demo.queue;

import com.test.redis.demo.DemoApplication;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.queue.operations.WebhookNotifier;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MockQueueIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:6.2.20-alpine").withExposedPorts(6379);

    @Test
    void defaultDemoUsesOnlyMockCallsAndFinalFailureMailLog(CapturedOutput output) {
        try (var app = new SpringApplicationBuilder(DemoApplication.class).run(
                "--spring.profiles.active=demo", "--spring.main.web-application-type=none",
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--queue.maintenance.enabled=false", "--queue.mock.retry-delay-ms=0",
                "--queue.alert.webhook-url=http://127.0.0.1:1/must-not-call")) {
            QueueProvider provider = app.getBean(QueueProvider.class);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(provider.counters()).containsEntry("succeeded", "2").containsEntry("failed", "1");
                assertThat(provider.getQueueSize(QueueType.USER.getProcessingKey())).isZero();
            });
            assertThat(app.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
            assertThat(app.getBean(WebhookNotifier.class).enabled()).isFalse();
            assertThat(provider.getQueueSize(QueueType.USER.getDlqKey())).isEqualTo(1);
            String log = output.getAll();
            assertThat(log).contains("[MOCK SUCCESS]", "[MOCK RETRY]", "[MOCK MAIL]");
            assertThat(log.split("\\[MOCK CALL\\]", -1)).hasSize(8); // 1 + 3 + 3 calls
            assertThat(log.split("\\[MOCK MAIL\\]", -1)).hasSize(2); // only exhausted job
            // Client retries do not dequeue/requeue the job or advance the next job.
            var dlq = provider.dlq(QueueType.USER, 0, 10).get(0);
            assertThat(dlq).containsEntry("attempts", "1");
        }
    }
}
