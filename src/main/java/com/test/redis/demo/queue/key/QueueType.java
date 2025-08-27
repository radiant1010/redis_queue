package com.test.redis.demo.queue.key;

import lombok.Getter;

@Getter
public enum QueueType {
    USER("staging:user-jobs",
            "queue:pending:user-jobs",
            "queue:processing:user-jobs",
            "queue:dlq:user-jobs");

    private final String stagingKey; // 데이터 처리를 위한 임시 저장소 키
    private final String pendingKey; // 큐에 등록하여 잠시 대기중인 상태를 나타내는 키
    private final String processingKey; // 작업 처리중인 작업 목록 키
    private final String dlqKey; // 작업 실패 목록 키

    QueueType(String stagingKey, String pendingKey, String processingKey, String dlqKey) {
        this.stagingKey = stagingKey;
        this.pendingKey = pendingKey;
        this.processingKey = processingKey;
        this.dlqKey = dlqKey;
    }

    // TODO : 실제 적용 시에는 사용될 key값만 남겨두고 접두사를 붙여 일괄적으로 관리 되도록 getter 구현
}
