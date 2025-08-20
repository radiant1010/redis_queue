package com.test.redis.demo.queue.dto;

public record JobPayload<T>(
        String jobType,
        T data
) {
}