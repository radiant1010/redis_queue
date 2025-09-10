package com.test.redis.demo.queue.consumer;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.bellins.groupPortal.queue.handler.JobHandler;
import net.bellins.groupPortal.queue.key.QueueType;
import net.bellins.groupPortal.queue.provider.QueueProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Configuration
@Slf4j
public class JobConsumerConfig {
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private QueueJobConsumer userJobConsumer;
    private QueueJobConsumer dualEmplJobConsumer;

    // DUAL_EMPL_USER 큐를 처리하는 Consumer Bean 생성
    @Bean
    public QueueJobConsumer dualEmplJobConsumer(QueueProvider queueProvider, List<JobHandler> allHandlers) {
        // DUAL_EMPL_USER 큐에 속한 핸들러만 필터링합니다.
        List<JobHandler> dualEmplHandlers = allHandlers.stream()
                .filter(handler -> handler.getJobType().getQueueType() == QueueType.DUAL_EMPL_USER)
                .collect(Collectors.toList());

        this.dualEmplJobConsumer = new QueueJobConsumer(QueueType.DUAL_EMPL_USER, queueProvider, dualEmplHandlers);
        executorService.submit(this.dualEmplJobConsumer); // 생성 후 스레드 풀에서 실행
        return this.dualEmplJobConsumer;
    }

    // 애플리케이션 종료 시, 실행 중인 Consumer들을 안전하게 중지시킵니다.
    @PreDestroy
    public void shutdown() {
        if (userJobConsumer != null) userJobConsumer.stop();
        if (dualEmplJobConsumer != null) dualEmplJobConsumer.stop();
        executorService.shutdown();
        log.info("모든 Job Consumer들을 안전하게 종료했습니다.");
    }
}
