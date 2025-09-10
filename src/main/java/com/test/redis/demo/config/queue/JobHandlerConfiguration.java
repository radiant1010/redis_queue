package com.test.redis.demo.config.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.util.SystemUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

// Redis Staging Queue 작업 등록 프리셋 셋업
@Configuration
public class JobHandlerConfiguration {

    // 예시 템플릿, 실제로 구현되는 코드는 Bean으로 등록
    @Bean
    public StagingManageService<UserDTO> userStagingService(
            // TODO : 추후 프리셋으로 등록한 다음 사용(ex. 팩토리 메서드)
            SystemUtil systemUtil,
            QueueProvider queueProvider,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper
    ) {
        return new StagingManageService<>(
                systemUtil,
                queueProvider,
                redisTemplate,
                objectMapper,
                QueueType.USER,
                JobType.USER_ADD,
                new TypeReference<List<UserDTO>>() {
                }
        );
    }
}
