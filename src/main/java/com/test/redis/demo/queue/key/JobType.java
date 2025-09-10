package com.test.redis.demo.queue.key;

import lombok.Getter;

@Getter
public enum JobType {
    USER_ADD(QueueType.USER);

    private final QueueType queueType;

    JobType(QueueType queueType) {
        this.queueType = queueType;
    }
}
