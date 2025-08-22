package com.test.redis.demo.queue.handler;

import com.test.redis.demo.queue.key.JobType;

// Redis Queue 처리를 위한 Job 핸들러
public interface JobHandler<T> {
    JobType getJobType(); // 실행시킬 Job 타입

    boolean process(String jobId); // DB 혹은 NOSQL에 저장된 데이터를 불러옴
}
