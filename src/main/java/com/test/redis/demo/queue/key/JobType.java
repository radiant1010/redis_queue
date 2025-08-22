package com.test.redis.demo.queue.key;

import lombok.Getter;

@Getter
public enum JobType {
    USER_ADD,
    USER_UPDATE,
    USER_DELETE;
}
