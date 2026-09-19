package com.test.redis.demo.queue.handler.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!database")
@Slf4j
public class LoggingFailureNotification implements FailureNotification {
    @Override
    public void notifyFailure(String jobId, String reason) {
        log.warn("[MOCK MAIL] final failure: jobId={}, reason={} (no email sent)", jobId, reason);
    }
}
