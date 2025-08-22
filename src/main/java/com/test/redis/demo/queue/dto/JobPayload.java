package com.test.redis.demo.queue.dto;

import com.test.redis.demo.queue.key.JobType;

public record JobPayload<T>(
        JobType jobType,
        T data
) {
}