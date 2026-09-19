package com.test.redis.demo.queue.handler.user;

/** Replace this adapter with the application's mail service. */
public interface FailureNotification {
    void notifyFailure(String jobId, String reason);
}
