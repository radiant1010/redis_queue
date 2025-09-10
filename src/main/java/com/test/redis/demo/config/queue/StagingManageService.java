package com.test.redis.demo.config.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.payload.JobPayload;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.util.SystemUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class StagingManageService<T> {

    private final SystemUtil systemUtil;
    private final QueueProvider queueProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private final QueueType queueType;
    private final JobType jobType;
    // Jackson이 제네릭 타입을 변환할 때 필요한 정보
    private final TypeReference<List<T>> listTypeReference;

    public String processAndEnqueue(List<T> data) {
        String jobId = stageData(data);
        JobPayload payload = createPayload(jobId);
        enqueueJob(payload);
        return jobId;
    }

    // JobPayload 생성
    private JobPayload createPayload(String jobId) {
        // 생성자에서 주입받은 jobType 사용
        return new JobPayload(this.jobType, jobId);
    }

    // Redis 해시에 값을 저장
    private String stageData(List<T> data) {
        String jobId = systemUtil.generatedUuid();
        // 생성자에서 주입받은 queueType 사용
        redisTemplate.opsForHash().put(queueType.getStagingKey(), jobId, data);
        return jobId;
    }

    // Redis 해시에 등록된 값을 조회
    public List<T> getStagedData(String jobId) {
        Object rawData = redisTemplate.opsForHash().get(queueType.getStagingKey(), jobId);
        if (rawData == null) {
            return null;
        }
        // 생성자에서 주입받은 TypeReference를 사용하여 정확한 타입으로 변환
        return objectMapper.convertValue(rawData, this.listTypeReference);
    }

    // Redis 해시에 등록된 값을 삭제
    public void deleteStagedData(String jobId) {
        redisTemplate.opsForHash().delete(queueType.getStagingKey(), jobId);
    }

    // Queue에 삽입
    private void enqueueJob(JobPayload payload) {
        // 생성자에서 주입받은 queueType 사용
        queueProvider.enqueue(queueType.getPendingKey(), payload);
    }
}
