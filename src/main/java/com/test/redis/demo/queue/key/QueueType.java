package com.test.redis.demo.queue.key;

import lombok.Getter;

@Getter
public enum QueueType {
    USER("queue:user-jobs", "staging:user-jobs");

    private final String queueKey; // 큐 작업 처리를 위한 작업 키
    private final String stagingKey; // 데이터 처리를 위한 임시 저장소 키


    QueueType(String queueKey, String stagingKey) {
        this.queueKey = queueKey;
        this.stagingKey = stagingKey;
    }
}
