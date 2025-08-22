package com.test.redis.demo.suport.redis;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class AbstractRedisUnitTestContainer {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:6.2"))
                    .withExposedPorts(6379);

    protected RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        String host = redisContainer.getHost();
        Integer port = redisContainer.getMappedPort(6379);

        RedisConnectionFactory connectionFactory = new LettuceConnectionFactory(host, port);
        ((LettuceConnectionFactory) connectionFactory).afterPropertiesSet();

        this.redisTemplate = new RedisTemplate<>();
        this.redisTemplate.setConnectionFactory(connectionFactory);

        // Serializer 설정 수정
        this.redisTemplate.setKeySerializer(new StringRedisSerializer());
        // 기본 생성자를 사용하도록 변경
        this.redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        this.redisTemplate.afterPropertiesSet();
    }
}
