package com.test.redis.demo.queue.operations;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queue.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class QueueMaintenance {
    private final QueueMonitor monitor;

    @Scheduled(fixedDelayString = "${queue.maintenance.interval-ms:15000}",
            initialDelayString = "${queue.maintenance.interval-ms:15000}")
    public void run() { monitor.tick(System.currentTimeMillis()); }
}
