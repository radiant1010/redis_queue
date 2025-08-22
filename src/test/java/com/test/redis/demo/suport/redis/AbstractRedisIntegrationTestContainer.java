package com.test.redis.demo.suport.redis;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
public abstract class AbstractRedisIntegrationTestContainer {
    // 테스트 클래스 전체에서 단 하나의 컨테이너만 공유하도록 static으로 선언합니다.
    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:6.2"))
                    // 컨테이너 내부의 6379 포트를 외부에 노출시킵니다.
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379).toString());
    }
}
