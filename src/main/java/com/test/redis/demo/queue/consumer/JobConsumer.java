package com.test.redis.demo.queue.consumer;

// Consumer execution and cooperative shutdown contract.
public interface JobConsumer {
    void start();
    void consume();
    void stop();
    default void abort() { stop(); }
}
