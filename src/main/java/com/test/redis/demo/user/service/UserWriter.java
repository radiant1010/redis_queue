package com.test.redis.demo.user.service;

import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.queue.handler.JobProcessingException;
import static com.test.redis.demo.queue.handler.JobProcessingException.Code.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@org.springframework.context.annotation.Profile("database")
@RequiredArgsConstructor
public class UserWriter {
    private final JdbcTemplate jdbc;

    public boolean isProcessed(String jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM processed_job WHERE job_id = ?", Boolean.class, jobId));
    }

    // The receipt and business rows commit together. A failed insert rolls back both.
    @Transactional
    public void insertOnce(String jobId, List<UserDTO> users) {
        if (isProcessed(jobId)) return;
        if (users == null) throw new JobProcessingException(MISSING_STAGING);
        if (users.isEmpty()) throw new JobProcessingException(EMPTY_DATA);
        jdbc.update("INSERT INTO processed_job(job_id) VALUES (?)", jobId);
        for (int i = 0; i < users.size(); i++) {
            UserDTO user = users.get(i);
            if (user == null || user.email() == null || !user.email().contains("@")
                    || user.displayName() == null || user.displayName().isBlank()) {
                throw new JobProcessingException(INVALID_USER);
            }
            jdbc.update("INSERT INTO app_user(job_id, item_index, email, display_name) VALUES (?, ?, ?, ?)",
                    jobId, i, user.email(), user.displayName());
        }
    }
}
