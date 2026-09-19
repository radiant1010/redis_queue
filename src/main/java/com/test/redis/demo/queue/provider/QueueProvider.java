package com.test.redis.demo.queue.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.operations.QueuePolicy;
import com.test.redis.demo.queue.payload.JobPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QueueProvider {
    public static final String INDEX = "queue:jobs";
    public static final String RETENTION = "queue:retention";
    public static final String COUNTERS = "queue:counters";
    private static final DefaultRedisScript<Long> WORKFLOW = workflowScript();
    private static DefaultRedisScript<Long> workflowScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("queue/workflow.lua"));
        script.setResultType(Long.class);
        return script;
    }
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final QueuePolicy policy;

    public static String metadataKey(String id) { return "queue:job:" + id; }
    public static String dataKey(QueueType queue, String id) { return queue.getStagingKey() + ":" + id; }

    public void stageAndEnqueue(String stagingKey, String pendingKey, JobPayload job, Object data) {
        QueueType queue = job.jobType().getQueueType();
        if (!stagingKey.equals(queue.getStagingKey()) || !pendingKey.equals(queue.getPendingKey())) {
            throw new IllegalArgumentException("Queue keys do not match job type");
        }
        requireSuccess(transition("REGISTER", job, toJson(data), "", System.currentTimeMillis()));
    }

    // Low-level registration without staging, useful for handlers with an external data source.
    public void enqueue(String queueName, JobPayload job) {
        if (!queueName.equals(job.jobType().getQueueType().getPendingKey())) {
            throw new IllegalArgumentException("Unknown pending queue");
        }
        requireSuccess(transition("REGISTER", job, "", "", System.currentTimeMillis()));
    }

    public Optional<JobPayload> dequeueAndMoveToProcessing(String pendingKey, String processingKey, Duration timeout) {
        String json = redisTemplate.opsForList().rightPopAndLeftPush(pendingKey, processingKey, timeout);
        if (json == null) return Optional.empty();
        JobPayload job = fromJson(json);
        QueueType queue = job.jobType().getQueueType();
        if (!queue.getPendingKey().equals(pendingKey) || !queue.getProcessingKey().equals(processingKey)) {
            throw new IllegalStateException("Payload belongs to another queue");
        }
        requireSuccess(transition("START", job, "", "", System.currentTimeMillis()));
        return Optional.of(job);
    }

    public void removeProcessingQueue(String processingKey, JobPayload job) {
        requireSuccess(transition("SUCCESS", job, "", "", System.currentTimeMillis()));
    }

    public void moveToDlq(String processingKey, String dlqKey, JobPayload job) {
        fail(job, "HANDLER_RETURNED_FALSE");
    }

    public void fail(JobPayload job, String reason) {
        requireSuccess(transition("FAIL", job, "", reason, System.currentTimeMillis()));
    }

    public void recover(QueueType queue) {
        List<String> entries = redisTemplate.opsForList().range(queue.getProcessingKey(), 0, -1);
        if (entries == null) return;
        // Validate all entries first. Invalid legacy/corrupt data is never silently removed.
        List<JobPayload> jobs = entries.stream().map(this::fromJson).toList();
        for (JobPayload job : jobs) {
            if (job.jobType().getQueueType() != queue || metadata(job.jobId()).isEmpty()) {
                throw new IllegalStateException("Cannot recover untracked job " + job.jobId());
            }
        }
        for (JobPayload job : jobs) {
            requireSuccess(transition("RECOVER", job, "", "", System.currentTimeMillis()));
        }
    }

    public void retry(String id) {
        requireSuccess(transition("RETRY", findJob(id), "", "", System.currentTimeMillis()));
    }

    public void cleanup(long now) {
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(RETENTION, 0, now, 0, 100);
        if (due != null) for (String id : due) transition("CLEANUP", findJob(id), "", "", now);
    }

    public Map<String, String> metadata(String id) {
        return redisTemplate.<String, String>opsForHash().entries(metadataKey(id));
    }

    public List<Map<String, String>> jobs(long offset, long limit) {
        Set<String> ids = redisTemplate.opsForZSet().reverseRange(INDEX, offset, offset + limit - 1);
        return ids == null ? List.of() : ids.stream().map(this::metadata).toList();
    }

    public List<Map<String, String>> dlq(QueueType queue, long offset, long limit) {
        List<String> entries = redisTemplate.opsForList().range(queue.getDlqKey(), offset, offset + limit - 1);
        return entries == null ? List.of() : entries.stream().map(this::fromJson)
                .map(job -> metadata(job.jobId())).toList();
    }

    public Map<String, String> counters() {
        return redisTemplate.<String, String>opsForHash().entries(COUNTERS);
    }

    public long expiringCount(long now, long warningMillis) {
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(RETENTION, 0, now + warningMillis, 0, 100);
        return ids == null ? 0 : ids.stream().map(this::metadata)
                .filter(meta -> "DLQ".equals(meta.get("status"))).count();
    }

    public long oldestAgeMillis(String queueKey, long now) {
        String raw = redisTemplate.opsForList().index(queueKey, -1);
        if (raw == null) return 0;
        Map<String, String> meta = metadata(fromJson(raw).jobId());
        return Math.max(0, now - Long.parseLong(meta.getOrDefault("updatedAt", Long.toString(now))));
    }

    public long getQueueSize(String queueName) {
        Long size = redisTemplate.opsForList().size(queueName);
        return size == null ? 0 : size;
    }

    private JobPayload findJob(String id) {
        Map<String, String> meta = metadata(id);
        if (meta.isEmpty()) throw new NoSuchElementException("Job not found");
        return new JobPayload(JobType.valueOf(meta.get("jobType")), id);
    }

    private Long transition(String operation, JobPayload job, String data, String reason, long now) {
        QueueType q = job.jobType().getQueueType();
        return redisTemplate.execute(WORKFLOW, List.of(dataKey(q, job.jobId()), metadataKey(job.jobId()),
                q.getPendingKey(), q.getProcessingKey(), q.getDlqKey(), INDEX, RETENTION, COUNTERS),
                operation, toJson(job), Long.toString(now), data, reason,
                Long.toString(policy.getDlqRetention().toMillis()),
                Long.toString(policy.getMetadataRetention().toMillis()), Integer.toString(policy.getMaxAttempts()));
    }

    private void requireSuccess(Long result) {
        if (result == null || result != 1) throw new IllegalStateException(switch (result == null ? 0 : result.intValue()) {
            case -2 -> "Job data retention deadline has passed";
            case -3 -> "Maximum attempts reached";
            case -4 -> "Staging data is missing";
            default -> "Job state conflict or duplicate job ID";
        });
    }

    private JobPayload fromJson(String json) {
        try {
            JobPayload job = objectMapper.readValue(json, JobPayload.class);
            if (job == null) throw new IllegalArgumentException("Null job payload");
            return job;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid job payload; original preserved in Redis", e);
        }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize queue data", e); }
    }
}
