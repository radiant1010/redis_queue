package com.test.redis.demo.config.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.util.SystemUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

// Redis Staging Queue 작업 등록 v2 프리셋 셋업
@Configuration
public class JobHandlerConfiguration {

    // 겸직 처리
    @Bean
    public StagingManageService<String> dualEmplStagingService(
            SystemUtil systemUtil,
            QueueProvider queueProvider,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper
    ) {
        // new 키워드를 사용해 직접 객체를 생성하고, 설정값들을 명시적으로 전달합니다.
        return new StagingManageService<>(
                systemUtil,
                queueProvider,
                redisTemplate,
                objectMapper,
                QueueType.USER,
                JobType.USER_ADD,
                new TypeReference<List<String>>() {} // Staging에 저장 시킬 값
        );
    }
}
