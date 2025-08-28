package com.test.redis.demo.queue.handler;

import com.test.redis.demo.queue.key.JobType;

// Redis Queue 처리를 위한 Job 핸들러
public interface JobHandler {
    // 작업 타입을 선언해두면 consumer에서 각 타입에 맞는 핸들러를 호출 한다.
    JobType getJobType();

    boolean process(String jobId); // DB 혹은 NOSQL에 저장된 데이터를 불러와 작업 처리 진행
}
