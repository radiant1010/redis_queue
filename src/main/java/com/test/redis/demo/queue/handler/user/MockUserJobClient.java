package com.test.redis.demo.queue.handler.user;

import com.test.redis.demo.user.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Profile("!database")
@Slf4j
public class MockUserJobClient implements UserJobClient {
    private final long retryDelayMillis;

    public MockUserJobClient(@Value("${queue.mock.retry-delay-ms:100}") long retryDelayMillis) {
        if (retryDelayMillis < 0) throw new IllegalArgumentException("Negative retry delay");
        this.retryDelayMillis = retryDelayMillis;
    }

    @Override
    public void execute(String jobId, List<UserDTO> users) {
        if (users == null || users.isEmpty()) throw new IllegalArgumentException("MISSING_STAGING");
        // Reserved fixture addresses control only the local mock. No HTTP requests are sent.
        String email = users.get(0).email();
        int failures = "retry@example.invalid".equals(email) ? 2
                : "fail@example.invalid".equals(email) ? 3 : 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CLIENT_INTERRUPTED");
            log.info("[MOCK CALL] jobId={}, attempt={}/3", jobId, attempt);
            if (attempt > failures) {
                log.info("[MOCK SUCCESS] jobId={}, attempt={}", jobId, attempt);
                return;
            }
            log.warn("[MOCK FAILURE] jobId={}, attempt={}", jobId, attempt);
            if (attempt == 3) throw new IllegalStateException("MOCK_RETRIES_EXHAUSTED");
            log.info("[MOCK RETRY] jobId={}, nextAttempt={}", jobId, attempt + 1);
            try {
                Thread.sleep(retryDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CLIENT_INTERRUPTED", e);
            }
        }
    }
}
