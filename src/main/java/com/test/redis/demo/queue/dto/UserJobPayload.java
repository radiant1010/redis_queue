package com.test.redis.demo.queue.dto;

import com.test.redis.demo.queue.key.JobType;

public record UserJobPayload(
        JobType jobType,
        String jobId
) {
}