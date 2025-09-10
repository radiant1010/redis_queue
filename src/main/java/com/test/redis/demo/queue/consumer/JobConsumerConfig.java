package com.test.redis.demo.queue.consumer;

import com.test.redis.demo.queue.handler.JobHandler;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@Slf4j
public class JobConsumerConfig {

    // USER 큐를 처리하는 Consumer Bean 생성
    @Bean
    public QueueJobConsumer userJobConsumer(QueueProvider queueProvider, List<JobHandler> allHandlers) {
        List<JobHandler> userHandlers = allHandlers.stream()
                .filter(handler -> handler.getJobType().getQueueType() == QueueType.USER)
                .collect(Collectors.toList());

        // 필터링된 핸들러가 없는 경우 로그를 남겨서 디버깅을 쉽게 할 수 있습니다.
        if (userHandlers.isEmpty()) {
            log.warn("USER 타입에 해당하는 JobHandler를 찾을 수 없습니다.");
        }
        return new QueueJobConsumer(QueueType.USER, queueProvider, userHandlers);

    }
}
