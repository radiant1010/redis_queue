package com.test.redis.demo.queue.dto;

import com.test.redis.demo.queue.key.JobType;
/*
* jobType : 작업을 수행할 job 유형(해당 유형별로 consumer는 handler를 파악해 process를 실행 시킨다)
* jobId : staging queue에 등록된 데이터(List<?>)를 조회하기 위한 jobId
* */
public record JobPayload(
        JobType jobType,
        String jobId
) {
}