package com.test.redis.demo.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.queue.consumer.QueueJobConsumer;
import com.test.redis.demo.queue.consumer.executor.JobConsumerExecutor;
import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.handler.user.UserAddHandler;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.payload.JobPayload;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@org.springframework.test.context.ActiveProfiles("database")
@SpringBootTest(properties = {"queue.consumer.enabled=false", "queue.maintenance.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:queue-test;DB_CLOSE_DELAY=-1", "queue.ops.token=test-secret"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisQueueIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:6.2.20-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final QueueType QUEUE = QueueType.USER;
    private static final Duration WAIT = Duration.ofSeconds(10);
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @Autowired QueueProvider provider;
    @Autowired StagingManageService<UserDTO> staging;
    @Autowired UserService users;
    @Autowired UserAddHandler userHandler;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.test.redis.demo.user.service.UserWriter writer;
    @Autowired com.test.redis.demo.queue.operations.QueuePolicy policy;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    private final List<JobConsumerExecutor> executors = new ArrayList<>();

    @BeforeEach
    void clearQueues() {
        // This Redis instance belongs exclusively to this Testcontainers test class.
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM processed_job");
    }

    @AfterEach
    void stopConsumers() throws InterruptedException {
        for (JobConsumerExecutor executor : executors) {
            CountDownLatch stopped = new CountDownLatch(1);
            executor.stop(stopped::countDown);
            try {
                assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.destroy();
            }
        }
    }

    @Test
    void submissionRoundTripsWithApplicationSerializationAndFifo() {
        List<UserDTO> data = List.of(new UserDTO("demo@example.com", "example-only"));
        String first = users.submitJob(data);
        String second = users.submitJob(data);
        assertThat(staging.getStagedData(first)).isEqualTo(data);
        assertThat(claim()).isEqualTo(new JobPayload(JobType.USER_ADD, first));
        assertThat(claim()).isEqualTo(new JobPayload(JobType.USER_ADD, second));
        assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isZero();
        assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isEqualTo(2);
    }

    @Test
    void submittedJobRunsActualHandlerAndCleansUp() {
        String id = users.submitJob(List.of(new UserDTO("demo@example.com", "example-only")));
        start(userHandler);
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(staging.getStagedData(id)).isNull();
            assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isZero();
            assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isZero();
        });
        assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isZero();
    }

    @Test
    void applicationStartsConsumersAutomaticallyAndClosesCleanly() {
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.test.redis.demo.DemoApplication.class)
                .run("--spring.profiles.active=database,demo", "--spring.main.web-application-type=none",
                        "--queue.maintenance.enabled=false",
                        "--spring.datasource.url=jdbc:h2:mem:queue-test;DB_CLOSE_DELAY=-1",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379))) {
            assertThat(context.getBean(JobConsumerExecutor.class).isRunning()).isTrue();
            String id = context.getBean(UserService.class)
                    .submitJob(List.of(new UserDTO("app@example.com", "demo-only")));
            await().atMost(WAIT).untilAsserted(() -> {
                assertThat(staging.getStagedData(id)).isNull();
                assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isZero();
                assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isZero();
            });
        }
    }

    @Test
    void interruptedJobIsRecoveredBeforeExistingPending() {
        enqueue("interrupted", "next");
        claim();
        List<String> handled = new CopyOnWriteArrayList<>();
        start(handler(id -> { handled.add(id); return true; }));
        await().atMost(WAIT).untilAsserted(() -> assertThat(handled).containsExactly("interrupted", "next"));
        assertThat(provider.metadata("interrupted")).containsEntry("recoveries", "1")
                .containsEntry("attempts", "2").containsEntry("attempt.1.outcome", "INTERRUPTED");
    }

    @Test
    void failedJobRetainsStagingDataForInspection() {
        List<UserDTO> data = List.of(new UserDTO("failed@example.com", "demo-only"));
        String id = users.submitJob(data);
        start(handler(ignored -> false));
        await().atMost(WAIT).untilAsserted(() -> assertDlq(id));
        assertThat(staging.getStagedData(id)).isEqualTo(data);
    }

    @Test
    void failedDlqTransitionStopsBeforeNextJob() throws InterruptedException {
        enqueue("fail", "next");
        redis.opsForValue().set(QUEUE.getDlqKey(), "wrong-type");
        List<String> handled = new CopyOnWriteArrayList<>();
        QueueJobConsumer consumer = new QueueJobConsumer(QUEUE, provider,
                List.of(handler(id -> { handled.add(id); return false; })));
        consumer.start();
        Thread worker = new Thread(consumer::consume);
        worker.start();
        try {
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(handled).containsExactly("fail");
            assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isEqualTo(1);
            assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isEqualTo(1);
        } finally {
            consumer.stop();
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test
    void businessRowsAndReceiptRollbackTogether() {
        String id = users.submitJob(List.of(new UserDTO("ok@example.com", "OK"), new UserDTO("invalid", "Bad")));
        claim();
        assertThatThrownBy(() -> userHandler.process(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
        assertThat(writer.isProcessed(id)).isFalse();
        assertThat(staging.getStagedData(id)).hasSize(2);
    }

    @Test
    void duplicateExecutionDoesNotDuplicateDatabaseRows() {
        String id = users.submitJob(List.of(new UserDTO("once@example.com", "Once")));
        JobPayload job = claim();
        assertThat(userHandler.process(id)).isTrue();
        assertThat(userHandler.process(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE job_id = ?", Integer.class, id)).isEqualTo(1);
        provider.removeProcessingQueue(QUEUE.getProcessingKey(), job);
        assertThat(staging.getStagedData(id)).isNull();
        assertThat(provider.metadata(id)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void committedDatabaseSurvivesApplicationRestartBeforeRedisAck(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        String url = "jdbc:h2:file:" + directory.resolve("queue-db").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        String id;
        try (var first = databaseContext(url, false)) {
            id = first.getBean(UserService.class).submitJob(List.of(new UserDTO("crash@example.com", "Crash")));
            first.getBean(QueueProvider.class).dequeueAndMoveToProcessing(QUEUE.getPendingKey(), QUEUE.getProcessingKey(), Duration.ofSeconds(1)).orElseThrow();
            assertThat(first.getBean(UserAddHandler.class).process(id)).isTrue();
            // Simulate termination after DB commit, before the Redis SUCCESS transition.
            assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isEqualTo(1);
        }
        try (var restarted = databaseContext(url, true)) {
            await().atMost(WAIT).untilAsserted(() -> assertThat(provider.metadata(id)).containsEntry("status", "SUCCEEDED"));
            var database = restarted.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            assertThat(database.queryForObject("SELECT COUNT(*) FROM app_user WHERE job_id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT COUNT(*) FROM processed_job WHERE job_id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(provider.metadata(id)).containsEntry("recoveries", "1").containsEntry("attempts", "2");
            assertThat(staging.getStagedData(id)).isNull();
        }
    }

    private org.springframework.context.ConfigurableApplicationContext databaseContext(String url, boolean enabled) {
        return new org.springframework.boot.builder.SpringApplicationBuilder(com.test.redis.demo.DemoApplication.class)
                .run("--spring.profiles.active=database", "--spring.main.web-application-type=none", "--queue.maintenance.enabled=false",
                        "--queue.consumer.enabled=" + enabled, "--spring.datasource.url=" + url,
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
    }

    @Test
    void manualRetryPreservesIdAndHistoryAndCleansUpOnlyAfterSuccess() {
        String id = users.submitJob(List.of(new UserDTO("retry@example.com", "Retry")));
        JobPayload job = claim();
        provider.fail(job, "TRANSIENT_FAILURE");
        assertThat(provider.metadata(id)).containsEntry("attempt.1.error", "TRANSIENT_FAILURE");
        provider.retry(id);
        assertThat(provider.metadata(id)).containsEntry("status", "PENDING").doesNotContainKey("expiresAt");
        assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isZero();
        assertThat(staging.getStagedData(id)).isNotNull();
        assertThat(claim()).isEqualTo(job);
        assertThat(userHandler.process(id)).isTrue();
        provider.removeProcessingQueue(QUEUE.getProcessingKey(), job);
        assertThat(provider.metadata(id)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", "2")
                .containsEntry("attempt.1.outcome", "FAILED").containsEntry("attempt.2.outcome", "SUCCEEDED");
        assertThat(provider.counters()).containsEntry("failed", "1").containsEntry("succeeded", "1");
    }

    @Test
    void duplicateRetryAndAttemptLimitDoNotAddExtraQueueEntries() {
        String id = users.submitJob(List.of(new UserDTO("limit@example.com", "Limit")));
        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            provider.fail(claim(), "FAILURE");
            if (attempt < policy.getMaxAttempts()) {
                provider.retry(id);
                assertThatThrownBy(() -> provider.retry(id)).isInstanceOf(IllegalStateException.class);
                assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isEqualTo(1);
            }
        }
        assertThatThrownBy(() -> provider.retry(id)).hasMessageContaining("Maximum attempts");
        assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isZero();
        assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isEqualTo(1);
    }

    @Test
    void expiryRejectsRetryEvenBeforeCleanupRuns() {
        String id = users.submitJob(List.of(new UserDTO("expiry@example.com", "Expiry")));
        provider.fail(claim(), "FAILURE");
        redis.opsForHash().put(QueueProvider.metadataKey(id), "expiresAt", Long.toString(System.currentTimeMillis() - 1));
        assertThatThrownBy(() -> provider.retry(id)).hasMessageContaining("deadline");
        assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isEqualTo(1);
    }

    @Test
    void retentionExpiresFailedPayloadThenMetadataButNeverActiveJobsOrReceipts() {
        String completed = users.submitJob(List.of(new UserDTO("done@example.com", "Done")));
        JobPayload done = claim();
        userHandler.process(completed);
        provider.removeProcessingQueue(QUEUE.getProcessingKey(), done);
        String failed = users.submitJob(List.of(new UserDTO("failed@example.com", "Failed")));
        provider.fail(claim(), "FAILURE");
        String active = users.submitJob(List.of(new UserDTO("active@example.com", "Active")));
        claim();
        String pending = users.submitJob(List.of(new UserDTO("pending@example.com", "Pending")));

        long expiry = Long.parseLong(provider.metadata(failed).get("expiresAt"));
        provider.cleanup(expiry);
        assertThat(provider.metadata(failed)).containsEntry("status", "EXPIRED");
        assertThat(staging.getStagedData(failed)).isNull();
        assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isZero();
        assertThatThrownBy(() -> provider.retry(failed)).isInstanceOf(IllegalStateException.class);
        provider.cleanup(System.currentTimeMillis() + policy.getMetadataRetention().toMillis() + 1000);
        assertThat(provider.metadata(failed)).isEmpty();
        assertThat(provider.metadata(completed)).isEmpty();
        assertThat(writer.isProcessed(completed)).isTrue();
        assertThat(staging.getStagedData(active)).isNotNull();
        assertThat(staging.getStagedData(pending)).isNotNull();
        assertThat(provider.metadata(active)).containsEntry("status", "PROCESSING");
        assertThat(provider.metadata(pending)).containsEntry("status", "PENDING");
    }

    @Test
    void retryCancelsPayloadExpiration() {
        String id = users.submitJob(List.of(new UserDTO("keep@example.com", "Keep")));
        provider.fail(claim(), "FAILURE");
        long expiry = Long.parseLong(provider.metadata(id).get("expiresAt"));
        provider.retry(id);
        provider.cleanup(expiry + 1);
        assertThat(staging.getStagedData(id)).isNotNull();
        assertThat(provider.metadata(id)).containsEntry("status", "PENDING");
    }

    @Test
    void operationsApiRequiresTokenAndExposesMetadataWithoutPayload() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/ops/queues"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ops/jobs/users")
                        .header("Authorization", "Bearer wrong").contentType("application/json").content("[]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ops/jobs/users")
                        .header("Authorization", "Bearer test-secret").contentType("application/json")
                        .content("[{\"email\":\"private@example.com\",\"displayName\":\"Private\"}]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).get("jobId").asText();
        provider.fail(claim(), "TRANSIENT_FAILURE");
        String dlq = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/ops/dlq")
                        .header("Authorization", "Bearer test-secret"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dlq).contains(id, "TRANSIENT_FAILURE").doesNotContain("private@example.com", "Private");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ops/jobs/" + id + "/retry")
                        .header("Authorization", "Bearer test-secret"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ops/jobs/" + id + "/retry")
                        .header("Authorization", "Bearer test-secret"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/ops/jobs?limit=101")
                        .header("Authorization", "Bearer test-secret"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void failedWebhookDoesNotBlockJobsAndMonitoringRetriesDelivery() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var response = new java.util.concurrent.atomic.AtomicInteger(503);
        List<String> notifications = new CopyOnWriteArrayList<>();
        server.createContext("/hook", exchange -> {
            notifications.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(response.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            var notifier = new com.test.redis.demo.queue.operations.WebhookNotifier(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", mapper, policy);
            var monitor = new com.test.redis.demo.queue.operations.QueueMonitor(provider, List.of(), policy, notifier);
            String failed = users.submitJob(List.of(new UserDTO("private@example.com", "Private")));
            provider.fail(claim(), "TRANSIENT_FAILURE");
            long now = System.currentTimeMillis();
            monitor.tick(now);
            assertThat(notifier.status()).containsEntry("deliveryFailures", 1L);
            String next = users.submitJob(List.of(new UserDTO("next@example.com", "Next")));
            start(userHandler);
            await().atMost(WAIT).untilAsserted(() -> assertThat(provider.metadata(next)).containsEntry("status", "SUCCEEDED"));
            response.set(200);
            monitor.tick(now + 1);
            monitor.tick(now + 2);
            assertThat(notifications).hasSize(2);
            assertThat(notifications.get(1)).contains("DLQ failures since last notification: 1")
                    .doesNotContain("private@example.com", failed);
            assertThat(monitor.snapshot()).containsEntry("succeededAttempts", 1L)
                    .containsEntry("failedAttempts", 1L).containsEntry("failureRate", 0.5);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void monitorWarnsAboutStoppedConsumerOverdueWorkAndExpiry() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        List<String> notifications = new CopyOnWriteArrayList<>();
        server.createContext("/hook", exchange -> {
            notifications.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        QueueJobConsumer consumer = new QueueJobConsumer(QUEUE, provider, List.of());
        Thread worker = new Thread(consumer::consume);
        try {
            String expiredSoon = users.submitJob(List.of(new UserDTO("soon@example.com", "Soon")));
            provider.fail(claim(), "FAILURE");
            long now = System.currentTimeMillis();
            long deadline = now + 1000;
            redis.opsForHash().put(QueueProvider.metadataKey(expiredSoon), "expiresAt", Long.toString(deadline));
            redis.opsForZSet().add(QueueProvider.RETENTION, expiredSoon, deadline);
            consumer.start();
            redis.opsForList().leftPush(QUEUE.getPendingKey(), "not-json");
            worker.start();
            worker.join(5000);
            assertThat(consumer.getState()).isEqualTo("FAILED");
            // Remove only the deliberately corrupted test message to inspect the other conditions.
            redis.delete(QUEUE.getProcessingKey());
            String pending = users.submitJob(List.of(new UserDTO("old@example.com", "Old")));
            redis.opsForHash().put(QueueProvider.metadataKey(pending), "updatedAt",
                    Long.toString(now - policy.getStuckThreshold().toMillis() - 1));
            var notifier = new com.test.redis.demo.queue.operations.WebhookNotifier(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", mapper, policy);
            var monitor = new com.test.redis.demo.queue.operations.QueueMonitor(provider, List.of(consumer), policy, notifier);
            monitor.tick(now);
            assertThat(notifications.stream().anyMatch(n -> n.contains("Consumer stopped"))).isTrue();
            assertThat(notifications.stream().anyMatch(n -> n.contains("Queue work overdue"))).isTrue();
            assertThat(notifications.stream().anyMatch(n -> n.contains("retention deadline"))).isTrue();
            assertThat(staging.getStagedData(expiredSoon)).isNotNull();
        } finally {
            consumer.stop();
            worker.interrupt();
            worker.join(5000);
            server.stop(0);
        }
    }

    @Test
    void invalidSubmissionDoesNotWriteAnything() {
        assertThatIllegalArgumentException().isThrownBy(() -> users.submitJob(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> users.submitJob(null));
        assertThatIllegalArgumentException().isThrownBy(() -> users.submitJob(java.util.Arrays.asList((UserDTO) null)));
        assertThat(redis.hasKey(QUEUE.getStagingKey())).isFalse();
        assertThat(redis.hasKey(QUEUE.getPendingKey())).isFalse();
    }

    @Test
    void invalidPendingKeyDoesNotLeaveStagingData() {
        redis.opsForValue().set(QUEUE.getPendingKey(), "wrong-type");
        assertThatThrownBy(() -> users.submitJob(List.of(new UserDTO("demo@example.com", "demo"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.hasKey(QUEUE.getStagingKey())).isFalse();
        assertThat(redis.opsForValue().get(QUEUE.getPendingKey())).isEqualTo("wrong-type");
    }

    @Test
    void duplicateJobIdDoesNotOverwriteStagingOrEnqueueAgain() {
        JobPayload job = job("same-id");
        provider.stageAndEnqueue(QUEUE.getStagingKey(), QUEUE.getPendingKey(), job, List.of("original"));
        assertThatThrownBy(() -> provider.stageAndEnqueue(QUEUE.getStagingKey(), QUEUE.getPendingKey(), job, List.of("replacement")))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.opsForValue().get(QueueProvider.dataKey(QUEUE, job.jobId()))).isEqualTo("[\"original\"]");
        assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isEqualTo(1);
    }

    @Test
    void failedHandlerMovesToDlqAndContinuesNextJob() {
        enqueue("fail", "next");
        List<String> handled = new CopyOnWriteArrayList<>();
        start(handler(id -> { handled.add(id); return !id.equals("fail"); }));
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(handled).containsExactly("fail", "next");
            assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isZero();
            assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isEqualTo(1);
        });
        assertDlq("fail");
    }

    @Test
    void thrownHandlerExceptionMovesToDlq() {
        enqueue("throws");
        start(handler(id -> { throw new IllegalStateException("business failure"); }));
        await().atMost(WAIT).untilAsserted(() -> assertDlq("throws"));
        assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isZero();
    }

    @Test
    void missingHandlerMovesToDlq() {
        enqueue("no-handler");
        start();
        await().atMost(WAIT).untilAsserted(() -> assertDlq("no-handler"));
        assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isZero();
    }

    @Test
    void missingStagingDataMovesToDlq() {
        enqueue("missing-data");
        start(userHandler);
        await().atMost(WAIT).untilAsserted(() -> assertDlq("missing-data"));
    }

    @Test
    void dlqWriteFailureKeepsProcessingEntry() {
        enqueue("preserved");
        JobPayload job = claim();
        redis.opsForValue().set(QUEUE.getDlqKey(), "wrong-type");
        assertThatThrownBy(() -> provider.moveToDlq(QUEUE.getProcessingKey(), QUEUE.getDlqKey(), job))
                .isInstanceOf(RuntimeException.class);
        assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isEqualTo(1);
        redis.delete(QUEUE.getDlqKey());
        provider.moveToDlq(QUEUE.getProcessingKey(), QUEUE.getDlqKey(), job);
        assertDlq("preserved");
        assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isZero();
        assertThatThrownBy(() -> provider.moveToDlq(QUEUE.getProcessingKey(), QUEUE.getDlqKey(), job))
                .isInstanceOf(IllegalStateException.class);
        assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isEqualTo(1);
    }

    @Test
    void malformedPayloadIsPreservedAndStopsConsumer() throws InterruptedException {
        redis.opsForList().leftPush(QUEUE.getPendingKey(), "not-json");
        enqueue("next");
        List<String> handled = new CopyOnWriteArrayList<>();
        QueueJobConsumer consumer = new QueueJobConsumer(QUEUE, provider, List.of(handler(id -> { handled.add(id); return true; })));
        consumer.start();
        Thread worker = new Thread(consumer::consume);
        worker.start();
        try {
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(handled).isEmpty();
            assertThat(redis.opsForList().range(QUEUE.getProcessingKey(), 0, -1)).containsExactly("not-json");
            assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isEqualTo(1);
        } finally {
            consumer.stop();
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test
    void secondJobWaitsUntilFirstHandlerCompletes() throws InterruptedException {
        enqueue("first", "second");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        start(handler(id -> {
            events.add("start:" + id);
            if (id.equals("first")) {
                entered.countDown();
                awaitLatch(release);
            }
            events.add("end:" + id);
            return true;
        }));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).containsExactly("start:first");
            assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isEqualTo(1);
        } finally {
            release.countDown();
        }
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(events).containsExactly("start:first", "end:first", "start:second", "end:second"));
    }

    @Test
    void gracefulStopWaitsForCurrentJobAndLeavesPendingForRestart() throws InterruptedException {
        enqueue("first", "second");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        List<String> handled = new CopyOnWriteArrayList<>();
        JobConsumerExecutor executor = start(handler(id -> {
            handled.add(id);
            if (id.equals("first")) {
                entered.countDown();
                awaitLatch(release);
            }
            return true;
        }));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.stop(stopped::countDown);
            assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handled).containsExactly("first");
        assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isZero();
        assertThat(provider.getQueueSize(QUEUE.getPendingKey())).isEqualTo(1);
        executor.start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(handled).containsExactly("first", "second"));
    }

    @Test
    void interruptedHandlerPreservesProcessingInsteadOfDeadLettering() throws InterruptedException {
        enqueue("interrupted-call");
        CountDownLatch called = new CountDownLatch(1);
        JobConsumerExecutor executor = start(handler(id -> {
            Thread.currentThread().interrupt();
            called.countDown();
            throw new IllegalStateException("Interrupted client call");
        }));
        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        CountDownLatch stopped = new CountDownLatch(1);
        executor.stop(stopped::countDown);
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(provider.getQueueSize(QUEUE.getProcessingKey())).isEqualTo(1);
        assertThat(provider.getQueueSize(QUEUE.getDlqKey())).isZero();
        assertThat(provider.metadata("interrupted-call")).containsEntry("status", "PROCESSING");
    }

    @Test
    void idleConsumerStopsAfterBlockingPollTimeout() throws InterruptedException {
        JobConsumerExecutor executor = start(userHandler);
        CountDownLatch stopped = new CountDownLatch(1);
        executor.stop(stopped::countDown);
        assertThat(stopped.await(7, TimeUnit.SECONDS)).isTrue();
    }

    private JobConsumerExecutor start(JobHandler... handlers) {
        JobConsumerExecutor executor = new JobConsumerExecutor(List.of(new QueueJobConsumer(QUEUE, provider, List.of(handlers))));
        executors.add(executor);
        executor.start();
        return executor;
    }

    private JobHandler handler(Function<String, Boolean> action) {
        return new JobHandler() {
            public JobType getJobType() { return JobType.USER_ADD; }
            public boolean process(String id) { return action.apply(id); }
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Handler timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void enqueue(String... ids) {
        for (String id : ids) provider.enqueue(QUEUE.getPendingKey(), job(id));
    }

    private JobPayload job(String id) { return new JobPayload(JobType.USER_ADD, id); }

    private JobPayload claim() {
        return provider.dequeueAndMoveToProcessing(QUEUE.getPendingKey(), QUEUE.getProcessingKey(), Duration.ofSeconds(1)).orElseThrow();
    }

    private void assertDlq(String id) {
        try {
            assertThat(redis.opsForList().range(QUEUE.getDlqKey(), 0, -1)).containsExactly(mapper.writeValueAsString(job(id)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
