package com.test.redis.demo.user.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.redis.demo.queue.payload.JobPayload;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.util.SystemUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final QueueProvider queueProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SystemUtil systemUtil;
    private final ObjectMapper objectMapper;

    // 사용자 추가 요청 job을 queue에 등록(Redis hash에 데이터 저장 및 queue에 작업 등록)
    public String submitJob(List<UserDTO> users) {
        // 작업 처리를 위한 jobId 생성
        String jobId = stageUserData(users);
        // payload 생성
        JobPayload payload = createPayload(jobId);
        // queue 삽입
        enqueueUserJob(payload);

        log.debug("[사용자_추가] 요청이 접수되어 Queue에 추가 되었습니다. JobId : {}", jobId);

        return jobId;
    }

    // jobId를 기준으로 실제 DB에 저장 시킨다
    public boolean insertUser(List<UserDTO> users) {
        // 실제 코드 생략
        return !users.isEmpty();
    }

    // payload 반환
    private JobPayload createPayload(String jobId) {
        return new JobPayload(JobType.USER_ADD, jobId);
    }

    // redis 해시에 값을 저장
    private String stageUserData(List<UserDTO> users) {
        String jobId = systemUtil.generatedUuid();
        redisTemplate.opsForHash().put(QueueType.USER.getStagingKey(), jobId, users);
        return jobId;
    }

    // redis 해시에 등록된 값을 조회
    public List<UserDTO> getStagedUserData(String jobId) {
        Object rawData = redisTemplate.opsForHash().get(QueueType.USER.getStagingKey(), jobId);
        if (rawData == null) return null;
        return objectMapper.convertValue(rawData, new TypeReference<>() {
        });
    }

    // redis 해시에 등록된 값을 삭제
    public void deleteStagedUserData(String jobId) {
        redisTemplate.opsForHash().delete(QueueType.USER.getStagingKey(), jobId);
    }

    // queue에 삽입
    private void enqueueUserJob(JobPayload payload) {
        queueProvider.enqueue(QueueType.USER.getPendingKey(), payload);
    }
}
