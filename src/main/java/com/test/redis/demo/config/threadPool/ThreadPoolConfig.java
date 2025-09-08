package com.test.redis.demo.config.threadPool;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ThreadPoolConfig {

    @Bean(name = "jobConsumerTaskExecutor")
    public ThreadPoolTaskExecutor jobConsumerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5); // 기본 스레드 수
        executor.setMaxPoolSize(10); // 최대 스레드 수
        executor.setQueueCapacity(25); // 대기 큐 크기
        executor.setThreadNamePrefix("job-consumer-");

        // 애플리케이션 종료 시, 큐에 대기중인 작업을 모두 처리할 때까지 기다릴지 여부
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 대기 시간 (초)
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        return executor;
    }
}
