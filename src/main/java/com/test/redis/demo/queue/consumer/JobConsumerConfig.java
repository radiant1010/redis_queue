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
    public QueueJobConsumer dualEmplJobConsumer(QueueProvider queueProvider, List<JobHandler> allHandlers) {
        List<JobHandler> dualEmplHandlers = allHandlers.stream()
                .filter(handler -> handler.getJobType().getQueueType() == QueueType.USER)
                .collect(Collectors.toList());

        return new QueueJobConsumer(QueueType.USER, queueProvider, dualEmplHandlers);

    }
}
