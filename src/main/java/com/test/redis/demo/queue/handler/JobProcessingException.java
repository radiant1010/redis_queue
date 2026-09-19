package com.test.redis.demo.queue.handler;

/** Safe operational error codes; never include the submitted payload in failure metadata. */
public class JobProcessingException extends IllegalArgumentException {
    public enum Code { MISSING_STAGING, EMPTY_DATA, INVALID_USER }
    private final Code code;

    public JobProcessingException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code getCode() { return code; }
}
