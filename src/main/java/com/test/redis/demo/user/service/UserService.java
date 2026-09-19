package com.test.redis.demo.user.service;

import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.user.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {
    private final StagingManageService<UserDTO> userStagingService;

    public String submitJob(List<UserDTO> users) {
        return userStagingService.processAndEnqueue(users);
    }
}
