package com.test.redis.demo.queue.handler.user;

import com.test.redis.demo.user.dto.UserDTO;
import java.util.List;

/** A real WebClient adapter must await completion (including its retries) before returning. */
public interface UserJobClient {
    void execute(String jobId, List<UserDTO> users);
}
