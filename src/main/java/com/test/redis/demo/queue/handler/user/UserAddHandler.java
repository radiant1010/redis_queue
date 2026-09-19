package com.test.redis.demo.queue.handler.user;

import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.user.service.UserWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("database")
@Slf4j
@RequiredArgsConstructor
public class UserAddHandler implements JobHandler {
    private final UserWriter writer;
    private final StagingManageService<UserDTO> staging;

    @Override
    public JobType getJobType() { return JobType.USER_ADD; }

    @Override
    public boolean process(String jobId) {
        log.info("[{}] 작업 시작", jobId);
        // A committed receipt wins even if the staging payload is no longer available.
        if (!writer.isProcessed(jobId)) {
            try {
                writer.insertOnce(jobId, staging.getStagedData(jobId));
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Another transaction may have committed the same job after our first check.
                if (!writer.isProcessed(jobId)) throw e;
            }
        }
        // QueueProvider atomically cleans up Staging when acknowledging success.
        log.info("[{}] DB 반영 완료 (중복 요청 포함)", jobId);
        return true;
    }
}
