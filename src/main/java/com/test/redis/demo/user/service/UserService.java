package com.test.redis.demo.user.service;

import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.user.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final StagingManageService<UserDTO> userStagingService;


    // 사용자 추가 요청 job을 queue에 등록(Redis hash에 데이터 저장 및 queue에 작업 등록)
    public String submitJob(List<UserDTO> users) {
        return userStagingService.processAndEnqueue(users);
    }

    // jobId를 기준으로 실제 DB에 저장 시킨다
    public boolean insertUser(List<UserDTO> users) {
        // 실제 코드 생략
        return !users.isEmpty();
    }
}
