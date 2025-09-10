package com.test.redis.demo.queue.handler.user;

import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserAddHandler implements JobHandler {

    private final UserService userService;
    private final StagingManageService<UserDTO> userStagingService;

    @Override
    public JobType getJobType() {
        return JobType.USER_ADD;
    }

    @Override
    public boolean process(String jobId) {
        log.info("[{}] 작업 시작", jobId);
        try {
            // Redis hash 조회
            List<UserDTO> usersToInsert = userStagingService.getStagedData(jobId);

            if (usersToInsert == null || usersToInsert.isEmpty()) {
                return false;
            }

            boolean insertResult = userService.insertUser(usersToInsert);

            if (insertResult) {
                userStagingService.deleteStagedData(jobId);
            }

            return insertResult;
        } catch (Exception e) {
            log.error("[{}] Queue 작업 처리 도중 오류가 발생 하였습니다. {}", jobId, e.getMessage(), e);
            return false;
        }
    }
}
