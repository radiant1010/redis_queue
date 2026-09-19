package com.test.redis.demo.queue.operations;

import com.test.redis.demo.queue.consumer.QueueJobConsumer;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QueueMonitor {
    private final QueueProvider provider;
    private final List<QueueJobConsumer> consumers;
    private final QueuePolicy policy;
    private final WebhookNotifier notifier;
    private long notifiedFailures;
    private volatile String maintenanceError = "";

    public Map<String, Object> snapshot() {
        Map<String, String> counters = provider.counters();
        long succeeded = Long.parseLong(counters.getOrDefault("succeeded", "0"));
        long failed = Long.parseLong(counters.getOrDefault("failed", "0"));
        List<Map<String, Object>> queues = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (QueueType queue : QueueType.values()) {
            String state = consumers.stream().filter(c -> c.getQueueType() == queue)
                    .map(QueueJobConsumer::getState).findFirst().orElse("NOT_REGISTERED");
            queues.add(Map.of("queue", queue.name(), "consumerState", state,
                    "pending", provider.getQueueSize(queue.getPendingKey()),
                    "processing", provider.getQueueSize(queue.getProcessingKey()),
                    "dlq", provider.getQueueSize(queue.getDlqKey()),
                    "oldestPendingAgeMs", provider.oldestAgeMillis(queue.getPendingKey(), now),
                    "oldestProcessingAgeMs", provider.oldestAgeMillis(queue.getProcessingKey(), now)));
        }
        return Map.of("queues", queues, "succeededAttempts", succeeded, "failedAttempts", failed,
                "failureRate", succeeded + failed == 0 ? 0.0 : (double) failed / (succeeded + failed),
                "webhook", notifier.status(), "maintenanceError", maintenanceError);
    }

    public synchronized void tick(long now) {
        // If monitoring fails, still attempt notification even when Redis is unavailable.
        try {
            for (QueueJobConsumer consumer : consumers) {
                String key = "consumer-" + consumer.getQueueType();
                if ("FAILED".equals(consumer.getState())) {
                    notifier.send(key, "Consumer stopped: " + consumer.getQueueType(), now);
                } else notifier.clear(key);
            }
            long failed = Long.parseLong(provider.counters().getOrDefault("failed", "0"));
            if (failed < notifiedFailures) notifiedFailures = failed; // Redis counters were reset.
            if (failed > notifiedFailures && notifier.send("dlq",
                    "DLQ failures since last notification: " + (failed - notifiedFailures), now)) {
                notifiedFailures = failed;
            }
            for (QueueType queue : QueueType.values()) {
                long pendingAge = provider.oldestAgeMillis(queue.getPendingKey(), now);
                long processingAge = provider.oldestAgeMillis(queue.getProcessingKey(), now);
                if (Math.max(pendingAge, processingAge) >= policy.getStuckThreshold().toMillis()) {
                    notifier.send("stuck-" + queue, "Queue work overdue: " + queue
                            + ", pendingAgeMs=" + pendingAge + ", processingAgeMs=" + processingAge, now);
                } else notifier.clear("stuck-" + queue);
            }
            long expiring = provider.expiringCount(now, policy.getExpiryWarning().toMillis());
            if (expiring > 0) notifier.send("expiry", "DLQ payloads near/past retention deadline (sample): " + expiring, now);
            else notifier.clear("expiry");
            notifier.clear("maintenance");
            maintenanceError = "";
        } catch (Exception e) {
            maintenanceError = e.getClass().getSimpleName();
            notifier.send("maintenance", "Queue monitoring unavailable: " + maintenanceError, now);
        }
        try {
            provider.cleanup(now);
            notifier.clear("cleanup");
        } catch (Exception e) {
            maintenanceError = e.getClass().getSimpleName();
            notifier.send("cleanup", "Queue cleanup failed: " + maintenanceError, now);
        }
    }
}
