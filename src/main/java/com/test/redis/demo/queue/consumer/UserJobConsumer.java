package com.test.redis.demo.queue.consumer;

import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.payload.JobPayload;
import com.test.redis.demo.queue.provider.QueueProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class UserJobConsumer implements JobConsumer {

    private final String pendingKey = QueueType.USER.getPendingKey();
    private final String processingKey = QueueType.USER.getProcessingKey();
    private final String dlqKey = QueueType.USER.getDlqKey();

    private final QueueProvider queueProvider;
    private final Map<JobType, JobHandler> handlerMap;

    private volatile boolean running = true;

    // 외부에서 종료를 명령할 메소드
    public void stop() {
        log.warn("[{}] queue의 Consumer에게 중지 명령이 내려졌습니다.", pendingKey);
        this.running = false;
    }

    public UserJobConsumer(QueueProvider queueProvider, List<JobHandler> handlers) {
        this.queueProvider = queueProvider;
        // List를 JobType을 Key로 하는 Map으로 변환하여 핸들러 맵 구현(getJobType에 맞는 process 실행 목적)
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(JobHandler::getJobType, Function.identity()));
    }

    @Override
    public void consume() {
        log.info("UserJobConsumer가 [{}] queue 감시를 시작합니다.", pendingKey);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                queueProvider.dequeueAndMoveToProcessing(pendingKey, processingKey, Duration.ofSeconds(5))
                        .ifPresent(this::dispatch);
            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    log.warn("정상적인 어플리케이션 종료 신호로 인해 감시중인 Consumer 루프를 중단합니다.");
                } else {
                    log.error("UserJobConsumer 실행 중 예측하지 못한 에러 발생", e);
                }
            }
        }
        log.warn("UserJobConsumer가 [{}] queue 감시를 안전하게 종료합니다.", pendingKey);
    }

    private void dispatch(JobPayload job) {
        JobType jobType = job.jobType();
        JobHandler handler = handlerMap.get(jobType);

        if (handler != null) {
            boolean success = false;
            try {
                success = handler.process(job.jobId());
            } catch (Exception e) {
                log.error("Dispatcher가 Job 처리 중 에러 발생: {}", job, e);
            }

            // 성공, 실패에 따른 처리
            queueProvider.removeProcessingQueue(processingKey, job);
            // 실패 시 DLQ로 이동
            if (!success) {
                queueProvider.moveToDlq(dlqKey, job);
            }
        } else {
            log.error("올바른 {} Handler를 찾을 수 없습니다. DLQ로 이동합니다.", jobType);
            queueProvider.removeProcessingQueue(processingKey, job);
            queueProvider.moveToDlq(dlqKey, job);
        }
    }
}