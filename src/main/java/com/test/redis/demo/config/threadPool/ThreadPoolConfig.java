package com.test.redis.demo.config.threadPool;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;

@Configuration
public class ThreadPoolConfig {
    @Bean
    @Primary
    public TaskExecutor jobConsumerTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder
                .corePoolSize(1)
                .maxPoolSize(1) // 순차 처리 보장을 위해 1개로 고정
                .keepAlive(Duration.ofSeconds(60))
                .queueCapacity(100)
                .threadNamePrefix("job-consumer-") // 식별 접두사
                .awaitTermination(true)
                .awaitTerminationPeriod(Duration.ofSeconds(30)) // 종료 전 최대 대기 시간
                // TODO : 작업 reject 처리 핸들러 추가(Optional)
                .build();
    }
}
