package com.test.redis.demo.queue.handler.user;

import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.suport.redis.AbstractRedisIntegrationTestContainer;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.user.service.UserService;
import com.test.redis.demo.util.SystemUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// UserAddHandler 통합 테스트
class UserAddHandlerTest extends AbstractRedisIntegrationTestContainer {

    @Autowired
    private SystemUtil systemUtil;

    @Autowired
    private UserService userService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private UserAddHandler userAddHandler;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("[UserAddHandler 성공 시나리오] 작업 완료 후 Redis Hash에 등록된 JobId 까지 제거")
    public void successUserAddHandler() {
        // given : 구성 준비
        // JobId 생성
        String jobId = systemUtil.generatedUuid();

        // DTO 생성
        List<UserDTO> users = List.of(
                new UserDTO("test1@test.com", "pw1"),
                new UserDTO("tes2t@test.com", "pw2")
        );

        // Redis에 테스트 객체 삽입(userService.getStagedUserData 내부)
        redisTemplate.opsForHash().put(QueueType.USER.getStagingKey(), jobId, users);

        // when : process 호출
        boolean result = userAddHandler.process(jobId);

        // then : 검증
        // 최종 결과 확인
        assertThat(result).isTrue();
        
        // redis hash 안에 jobId가 제거 되었는지 확인
        Object stagedDataAfterProcess = redisTemplate.opsForHash().get(QueueType.USER.getStagingKey(), jobId);
        assertThat(stagedDataAfterProcess).isNull();
    }

}