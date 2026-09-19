package com.test.redis.demo.demo;

import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;

/** Opt-in demo: each application start with the demo profile submits three jobs. */
@Component
@Profile("demo")
@RequiredArgsConstructor
@Slf4j
public class QueueDemoRunner implements CommandLineRunner {
    private final UserService userService;
    private final org.springframework.core.env.Environment environment;

    @Override
    public void run(String... args) {
        boolean database = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("database"));
        String[] fixtures = {"success@example.invalid", "retry@example.invalid", "fail@example.invalid"};
        for (int i = 1; i <= 3; i++) {
            String email = database ? "demo" + i + "@example.com" : fixtures[i - 1];
            String jobId = userService.submitJob(List.of(new UserDTO(email, "Demo User " + i)));
            log.info("Demo job {} submitted: {}", i, jobId);
        }
    }
}
