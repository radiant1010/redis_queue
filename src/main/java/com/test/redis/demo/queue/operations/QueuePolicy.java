package com.test.redis.demo.queue.operations;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties("queue.policy")
public class QueuePolicy {
    private Duration dlqRetention = Duration.ofDays(30);
    private Duration metadataRetention = Duration.ofDays(90);
    private Duration expiryWarning = Duration.ofDays(1);
    private Duration stuckThreshold = Duration.ofMinutes(5);
    private Duration alertCooldown = Duration.ofMinutes(5);
    private int maxAttempts = 3;

    @PostConstruct
    public void validate() {
        if (dlqRetention.isNegative() || dlqRetention.isZero()
                || metadataRetention.compareTo(dlqRetention) <= 0
                || expiryWarning.isNegative() || expiryWarning.compareTo(dlqRetention) >= 0
                || stuckThreshold.isNegative() || stuckThreshold.isZero()
                || alertCooldown.isNegative() || alertCooldown.isZero() || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid queue retention / retry policy");
        }
    }
}
