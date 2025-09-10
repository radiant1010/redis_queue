package com.test.redis.demo.queue.consumer;

import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.payload.JobPayload;
import com.test.redis.demo.queue.provider.QueueProvider;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class QueueJobConsumer implements JobConsumer  {

    private final String consumerName;
    private final String pendingKey;
    private final String processingKey;
    private final String dlqKey;

    private final QueueProvider queueProvider;
    private final Map<JobType, JobHandler> handlerMap;

    private volatile boolean running = true;

    // 생성자에서 특정 QueueType과 그에 맞는 Handler 리스트를 주입받습니다.
    public QueueJobConsumer(QueueType queueType, QueueProvider queueProvider, List<JobHandler> handlers) {
        this.queueProvider = queueProvider;
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(JobHandler::getJobType, Function.identity()));

        // 주입받은 queueType으로 모든 Key를 설정합니다.
        this.pendingKey = queueType.getPendingKey();
        this.processingKey = queueType.getProcessingKey();
        this.dlqKey = queueType.getDlqKey();
        this.consumerName = queueType.name() + "-JobConsumer"; // 로그 가독성을 위한 이름
    }

    // 외부에서 종료를 명령할 메소드
    public void stop() {
        log.warn("[{}] Consumer에게 중지 명령이 내려졌습니다.", consumerName);
        this.running = false;
    }

    @Override
    public void consume() {
        log.info("[{}]가 [{}] queue 감시를 시작합니다.", consumerName, pendingKey);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Dequeue 로직은 동일
                queueProvider.dequeueAndMoveToProcessing(pendingKey, processingKey, Duration.ofSeconds(5))
                        .ifPresent(this::dispatch);
            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    log.warn("[{}] 정상적인 종료 신호로 인해 루프를 중단합니다.", consumerName);
                } else {
                    log.error("[{}] 실행 중 예측하지 못한 에러 발생", consumerName, e);
                }
            }
        }
        log.warn("[{}]가 [{}] queue 감시를 안전하게 종료합니다.", consumerName, pendingKey);
    }

    // dispatch 로직은 동일
    private void dispatch(JobPayload job) {
        JobType jobType = job.jobType();
        JobHandler handler = handlerMap.get(jobType);

        if (handler != null) {
            boolean success = false;
            try {
                success = handler.process(job.jobId());
            } catch (Exception e) {
                log.error("[{}] Job 처리 중 에러 발생: {}", consumerName, job, e);
            }

            queueProvider.removeProcessingQueue(processingKey, job);
            if (!success) {
                log.warn("[{}] Job 처리에 실패하여 DLQ로 이동합니다: {}", consumerName, job);
                queueProvider.moveToDlq(dlqKey, job);
            }
        } else {
            log.error("[{}] 올바른 {} Handler를 찾을 수 없습니다. DLQ로 이동합니다.", consumerName, jobType);
            queueProvider.removeProcessingQueue(processingKey, job);
            queueProvider.moveToDlq(dlqKey, job);
        }
    }
}