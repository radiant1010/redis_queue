package com.test.redis.demo.queue.operations;

import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
public class QueueOperationsController {
    private final QueueProvider provider;
    private final QueueMonitor monitor;
    private final UserService userService;

    @GetMapping("/queues")
    public Map<String, Object> queues() { return monitor.snapshot(); }

    @GetMapping("/jobs")
    public List<Map<String, String>> jobs(@RequestParam(defaultValue = "0") long offset,
                                         @RequestParam(defaultValue = "50") long limit) {
        validatePage(offset, limit);
        return provider.jobs(offset, limit);
    }

    @GetMapping("/dlq")
    public List<Map<String, String>> dlq(@RequestParam(defaultValue = "USER") QueueType queue,
                                        @RequestParam(defaultValue = "0") long offset,
                                        @RequestParam(defaultValue = "50") long limit) {
        validatePage(offset, limit);
        return provider.dlq(queue, offset, limit);
    }

    @GetMapping("/jobs/{id}")
    public Map<String, String> job(@PathVariable String id) {
        Map<String, String> meta = provider.metadata(id);
        if (meta.isEmpty()) throw new NoSuchElementException("Job not found");
        return meta;
    }

    @PostMapping("/jobs/users")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> submit(@RequestBody List<UserDTO> users) {
        return Map.of("jobId", userService.submitJob(users));
    }

    @PostMapping("/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> retry(@PathVariable String id) {
        provider.retry(id);
        return Map.of("jobId", id, "result", "REQUEUED");
    }

    private void validatePage(long offset, long limit) {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("offset must be 0..1000000 and limit 1..100");
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(NoSuchElementException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable() { return Map.of("error", "Queue storage unavailable"); }
}
