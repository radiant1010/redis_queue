package com.test.redis.demo.queue.handler.user;

import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.user.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!database")
@RequiredArgsConstructor
@Slf4j
public class MockUserAddHandler implements JobHandler {
    private final StagingManageService<UserDTO> staging;
    private final UserJobClient client;
    private final FailureNotification notification;

    @Override
    public JobType getJobType() { return JobType.USER_ADD; }

    @Override
    public boolean process(String jobId) {
        try {
            client.execute(jobId, staging.getStagedData(jobId));
            return true;
        } catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) {
                // Shutdown interruption is not a terminal business failure.
                throw new IllegalStateException("CLIENT_INTERRUPTED", failure);
            }
            try {
                notification.notifyFailure(jobId, failure.getClass().getSimpleName());
            } catch (Exception notificationFailure) {
                log.error("Failure notification adapter failed for jobId={}", jobId, notificationFailure);
            }
            return false;
        }
    }
}
