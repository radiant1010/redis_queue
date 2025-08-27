package com.test.redis.demo.queue.provider;

import com.test.redis.demo.queue.dto.JobPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobProvider {

    private final RedisTemplate<String, Object> redisTemplate;

    // queue에 작업 추가(non-blocking)
    public <T> void enqueue(String queueName, JobPayload<T> job) {
        log.debug("Queue 삽입 : queueKey={}, job={}", queueName, job);
        redisTemplate.opsForList().leftPush(queueName, job);
    }

    // queue에 작업 꺼내기(non-blocking)
    public Optional<JobPayload<?>> dequeue(String queueName) {
        log.debug("Queue 꺼내기 : queueKey={}", queueName);

        Object jobObject = redisTemplate.opsForList().rightPop(queueName);
        if (jobObject instanceof JobPayload<?> job) {
            // 잡 이름 반환
            return Optional.of(job);
        }
        return Optional.empty();
    }
    
    public long getQueueSize(String queueName) {
        Long size = redisTemplate.opsForList().size(queueName);
        return size != null ? size : 0;
    }
}
