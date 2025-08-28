package com.test.redis.demo.queue.consumer;

// Marker 인터페이스(consumer Executor 에서 실행 시킬 목적)
public interface JobConsumer {
    void consume();
}