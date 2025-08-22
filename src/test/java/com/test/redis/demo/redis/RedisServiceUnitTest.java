package com.test.redis.demo.redis;

import com.test.redis.demo.suport.redis.AbstractRedisUnitTestContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class RedisServiceUnitTest extends AbstractRedisUnitTestContainer {
    // Test용 dto 선언
    public record UserDto(String id, String name, int age) {
    }

    @Test
    @DisplayName("Redis TestContainer 설정 테스트")
    void redisTestWithBaseClass() {
        String key = "redis-key";
        UserDto userDto = new UserDto("1", "홍길동", 25);

        // when
        redisTemplate.opsForValue().set(key, userDto);
        Object testObject = redisTemplate.opsForValue().get(key);

        // verify
        Assertions.assertInstanceOf(UserDto.class, testObject);
        Assertions.assertEquals(userDto, testObject);

        System.out.println("Redis TestContainer 설정 테스트 성공! DTO : " + testObject);
    }
}
