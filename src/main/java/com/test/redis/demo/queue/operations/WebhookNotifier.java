package com.test.redis.demo.queue.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class WebhookNotifier {
    private final String url;
    private final ObjectMapper mapper;
    private final QueuePolicy policy;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Long> lastSent = new HashMap<>();
    private volatile String lastError = "";
    private volatile long deliveryFailures;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookNotifier(@Value("${queue.alert.webhook-url:}") String url, ObjectMapper mapper, QueuePolicy policy,
                           org.springframework.core.env.Environment environment) {
        this(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("database")) ? url : "", mapper, policy);
    }

    public WebhookNotifier(String url, ObjectMapper mapper, QueuePolicy policy) {
        this.url = url;
        this.mapper = mapper;
        this.policy = policy;
        if (!url.isBlank() && !java.util.Set.of("http", "https").contains(URI.create(url).getScheme())) {
            throw new IllegalArgumentException("Webhook URL must use HTTP or HTTPS");
        }
    }

    // Called by the maintenance worker, never by a job handler. Failed sends remain eligible.
    public synchronized boolean send(String key, String text, long now) {
        Long previous = lastSent.get(key);
        if (previous != null && now - previous < policy.getAlertCooldown().toMillis()) return false;
        if (!enabled()) {
            log.warn("Queue alert (webhook disabled): {}", text);
            lastSent.put(key, now);
            return true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("text", text))))
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP_" + status);
            lastSent.put(key, now);
            lastError = "";
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            deliveryFailures++;
            lastError = e instanceof IllegalStateException ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Webhook delivery failed: {}", lastError);
            return false;
        }
    }

    public synchronized void clear(String key) { lastSent.remove(key); }
    public boolean enabled() { return !url.isBlank(); }
    public Map<String, Object> status() {
        return Map.of("enabled", enabled(), "lastError", lastError, "deliveryFailures", deliveryFailures);
    }
}
