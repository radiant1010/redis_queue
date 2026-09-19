package com.test.redis.demo.config.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.payload.JobPayload;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.util.SystemUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
public class StagingManageService<T> {
    private final SystemUtil systemUtil;
    private final QueueProvider queueProvider;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final QueueType queueType;
    private final JobType jobType;
    private final TypeReference<List<T>> listTypeReference;

    public String processAndEnqueue(List<T> data) {
        if (data == null || data.isEmpty() || data.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Job data must not be empty or contain null items");
        }
        String jobId = systemUtil.generatedUuid();
        queueProvider.stageAndEnqueue(queueType.getStagingKey(), queueType.getPendingKey(),
                new JobPayload(jobType, jobId), data);
        return jobId;
    }

    public List<T> getStagedData(String jobId) {
        String json = redisTemplate.opsForValue().get(QueueProvider.dataKey(queueType, jobId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, listTypeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid staging data for " + jobId, e);
        }
    }
}
