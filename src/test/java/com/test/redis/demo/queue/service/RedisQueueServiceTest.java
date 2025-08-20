package com.test.redis.demo.queue.service;

import com.test.redis.demo.queue.dto.JobPayload;
import com.test.redis.demo.queue.provider.JobQueueProvider;
import com.test.redis.demo.suport.redis.AbstractRedisTestContainer;
import com.test.redis.demo.user.dto.UserDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class RedisQueueServiceTest extends AbstractRedisTestContainer {

    private final String queueName = "user-creation-queue";
    private JobQueueProvider queueProvider;

    @BeforeEach
    void init() {
        this.queueProvider = new JobQueueProvider(this.redisTemplate);
        redisTemplate.delete(queueName);
    }

    @Test
    @DisplayName("Redis Queue Push&POP 정상 처리 테스트")
    public void pushPopTest() {
        // given
        UserDTO user1 = new UserDTO("user1@example.com", "pw1");
        List<UserDTO> users1 = new ArrayList<>();
        users1.add(user1);

        UserDTO user2 = new UserDTO("user2@example.com", "pw1");
        List<UserDTO> users2 = new ArrayList<>();
        users1.add(user2);

        JobPayload<List<UserDTO>> payload1 = new JobPayload<>("INSERT_USER_TEST01", users1);
        JobPayload<List<UserDTO>> payload2 = new JobPayload<>("INSERT_USER_TEST02", users2);

        // when
        // 큐에 작업 2개 삽입
        queueProvider.enqueue(queueName, payload1);
        queueProvider.enqueue(queueName, payload2);

        // then
        // 큐에 작업이 2개가 쌓여 있는지 확인(INSERT_USER_TEST01, INSERT_USER_TEST02)
        Assertions.assertEquals(2, queueProvider.getQueueSize(queueName));

        // 가장 먼저 넣었던 INSERT_USER_TEST01가 빠져 나왔는지 확인
        JobPayload<?> firstDequeued = queueProvider.dequeue(queueName).orElseThrow();
        Assertions.assertEquals(payload1, firstDequeued);

        // 큐에 작업이 1개 남아있는지 확인(예측 값 INSERT_USER_TEST02)
        Assertions.assertEquals(1, queueProvider.getQueueSize(queueName));

        // 두 번째로 넣었던 INSERT_USER_TEST02가 맞는지 확인
        JobPayload<?> secondDequeued = queueProvider.dequeue(queueName).orElseThrow();
        Assertions.assertEquals(payload2, secondDequeued);

        // 모든 작업을 꺼낸 후, 큐가 비었는지 확인
        Assertions.assertEquals(0, queueProvider.getQueueSize(queueName));
    }
}