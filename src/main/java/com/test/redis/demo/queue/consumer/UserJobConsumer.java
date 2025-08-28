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

    public UserJobConsumer(QueueProvider queueProvider, List<JobHandler> handlers) {
        this.queueProvider = queueProvider;
        // List를 JobType을 Key로 하는 Map으로 변환하여 핸들러 맵 구현(getJobType에 맞는 process 실행 목적)
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(JobHandler::getJobType, Function.identity()));
    }

    @Override
    public void consume() {
        log.info("UserJobConsumer queue가 실행되었습니다. key={}", pendingKey);
        // 스레드가 인터럽트되지 않은 동안 계속 루프를 실행하여 인터럽트 발생 시 안전하게 종료할 수 있도록 조건 체크
        while (!Thread.currentThread().isInterrupted()) {
            try {
                queueProvider.dequeueAndMoveToProcessing(pendingKey, processingKey, Duration.ofSeconds(5))
                        .ifPresent(this::dispatch);
            } catch (Exception e) {
                log.error("UserJobConsumer 실행 도중 에러 발생 : {}", e.getMessage(), e);
            }
        }
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