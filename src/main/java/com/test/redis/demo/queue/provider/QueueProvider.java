package com.test.redis.demo.queue.provider;

import com.test.redis.demo.queue.dto.UserJobPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueProvider {

    private final RedisTemplate<String, Object> redisTemplate;

    // queue에 작업 추가(non-blocking)
    public <T> void enqueue(String queueName, UserJobPayload job) {
        log.debug("Queue 삽입 : queueKey={}, job={}", queueName, job);
        redisTemplate.opsForList().leftPush(queueName, job);
    }

    // queue에 작업 꺼내기(non-blocking)
    public Optional<UserJobPayload> dequeue(String queueName) {
        log.debug("Queue 꺼내기 : queueKey={}", queueName);

        Object jobObject = redisTemplate.opsForList().rightPop(queueName);
        if (jobObject instanceof UserJobPayload job) {
            // 잡 이름 반환
            return Optional.of(job);
        }
        return Optional.empty();
    }

    /*
     * pending 상태의 queue 리스트에서 작업을 꺼내 processing queue 으로 이동 시킨다.
     *
     * @param pendingQueueKey : 작업 대기중인 큐(queue:pending:user-jobs)
     * @param processingQueueKey : 작업 처리중인 큐(queue:processing:user-jobs)
     * @param timeout : 작업이 없을 시 대기 시간
     *
     */
    public Optional<UserJobPayload> dequeueAndMoveToProcessing(String pendingQueueKey, String processingQueueKey, Duration timeout) {
        // 작업을 안전하게 꺼내도록 처리
        Object jobObject = redisTemplate.opsForList().rightPopAndLeftPush(pendingQueueKey, processingQueueKey, timeout);
        if (jobObject instanceof UserJobPayload job) {
            return Optional.of(job);
        }
        return Optional.empty();
    }

    // processing queue에서 완료된 작업 제거
    public void removeProcessingQueue(String processingQueueKey, UserJobPayload job) {
        // count 1은 리스트의 앞에서부터 job과 일치하는 첫 번째 항목 1개를 제거 한다는 의미
        redisTemplate.opsForList().remove(processingQueueKey, 1, job);
    }

    // 작업 실패 queue로 이동 처리
    public void moveToDlq(String dlqKey, UserJobPayload job) {
        log.warn("작업 실패! DLQ key={}로 이동: {}", dlqKey, job);
        redisTemplate.opsForList().leftPush(dlqKey, job);
    }

    public long getQueueSize(String queueName) {
        Long size = redisTemplate.opsForList().size(queueName);
        return size != null ? size : 0;
    }
}
