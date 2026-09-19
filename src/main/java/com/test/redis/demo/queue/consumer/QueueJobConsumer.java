package com.test.redis.demo.queue.consumer;

import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.handler.JobProcessingException;
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
public class QueueJobConsumer implements JobConsumer {

    private final String consumerName;
    private final String pendingKey;
    private final String processingKey;
    private final QueueType queueType;

    private final QueueProvider queueProvider;
    private final Map<JobType, JobHandler> handlerMap;

    private volatile boolean running;
    private volatile boolean aborted;
    private volatile String state = "STOPPED";
    public String getState() { return state; }
    public QueueType getQueueType() { return queueType; }

    // 생성자에서 특정 QueueType과 그에 맞는 Handler 리스트를 주입받습니다.
    public QueueJobConsumer(QueueType queueType, QueueProvider queueProvider, List<JobHandler> handlers) {
        this.queueProvider = queueProvider;
        this.queueType = queueType;
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(JobHandler::getJobType, Function.identity()));

        // 주입받은 queueType으로 모든 Key를 설정합니다.
        this.pendingKey = queueType.getPendingKey();
        this.processingKey = queueType.getProcessingKey();
        this.consumerName = queueType.name() + "-JobConsumer"; // 로그 가독성을 위한 이름
    }

    // 외부에서 종료를 명령할 메소드
    @Override
    public void stop() {
        log.info("[{}] Consumer에게 중지 명령이 내려졌습니다.", consumerName);
        this.running = false;
        if (!"FAILED".equals(state)) state = "STOPPING";
    }

    @Override
    public void start() {
        queueProvider.recover(queueType);
        this.aborted = false;
        this.running = true;
        state = "RUNNING";
    }

    @Override
    public void abort() {
        this.aborted = true;
        stop();
    }

    private void checkCancellation() {
        if (aborted || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Job cancelled; preserved in Processing");
        }
    }

    @Override
    public void consume() {
        log.info("[{}]가 [{}] queue 감시를 시작합니다.", consumerName, pendingKey);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                queueProvider.dequeueAndMoveToProcessing(pendingKey, processingKey, Duration.ofSeconds(5))
                        .ifPresent(this::dispatch);
            } catch (Exception e) {
                if (!running && Thread.currentThread().isInterrupted()) {
                    log.warn("[{}] 종료 대기가 중단됐습니다. Processing에 남은 작업을 확인하세요.", consumerName, e);
                } else {
                    log.error("[{}] Queue 오류로 Consumer를 중지합니다. Processing 확인 후 재시작하세요.", consumerName, e);
                }
                running = false;
                state = "FAILED";
            }
        }
        if (!"FAILED".equals(state)) state = "STOPPED";
        log.info("[{}]가 [{}] queue 감시를 종료합니다.", consumerName, pendingKey);
    }

    private void dispatch(JobPayload job) {
        checkCancellation();
        JobType jobType = job.jobType();
        JobHandler handler = handlerMap.get(jobType);

        if (handler != null) {
            boolean success = false;
            String reason = "HANDLER_RETURNED_FALSE";
            try {
                success = handler.process(job.jobId());
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Job interrupted; preserved in Processing", e);
                }
                reason = e instanceof JobProcessingException failure
                        ? failure.getCode().name() : e.getClass().getSimpleName();
                log.error("[{}] Job 처리 중 에러 발생: {}", consumerName, job, e);
            }

            checkCancellation();
            if (success) {
                queueProvider.removeProcessingQueue(processingKey, job);
            } else {
                log.warn("[{}] Job 처리에 실패하여 DLQ로 이동합니다: {}", consumerName, job);
                queueProvider.fail(job, reason);
            }
        } else {
            log.error("[{}] 올바른 {} Handler를 찾을 수 없습니다. DLQ로 이동합니다.", consumerName, jobType);
            queueProvider.fail(job, "HANDLER_NOT_FOUND");
        }
    }
}
