package com.test.redis.demo.queue.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class WebhookNotifierTest {
    private HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private WebhookNotifier notifier;
    private final QueuePolicy policy = new QueuePolicy();

    @BeforeEach
    void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
        notifier = new WebhookNotifier("http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                new ObjectMapper(), policy);
    }

    @AfterEach
    void stopReceiver() { server.stop(0); }

    @Test
    void postsJsonAndSuppressesDuplicatesUntilCooldownOrRecovery() throws Exception {
        assertThat(notifier.send("failure", "DLQ failures: 2", 1000)).isTrue();
        assertThat(notifier.send("failure", "DLQ failures: 2", 1001)).isFalse();
        assertThat(bodies).hasSize(1);
        assertThat(new ObjectMapper().readTree(bodies.get(0)).get("text").asText()).isEqualTo("DLQ failures: 2");
        assertThat(notifier.send("failure", "Still failed", 1000 + policy.getAlertCooldown().toMillis())).isTrue();
        notifier.clear("failure");
        assertThat(notifier.send("failure", "New incident", 1001 + policy.getAlertCooldown().toMillis())).isTrue();
        assertThat(bodies).hasSize(3);
    }

    @Test
    void failedHttpResponseIsRetriedAndVisibleInStatus() {
        responseStatus.set(503);
        assertThat(notifier.send("failure", "test", 1000)).isFalse();
        assertThat(notifier.status()).containsEntry("deliveryFailures", 1L).containsEntry("lastError", "HTTP_503");
        responseStatus.set(200);
        assertThat(notifier.send("failure", "test", 1001)).isTrue();
        assertThat(bodies).hasSize(2);
        assertThat(notifier.status()).containsEntry("lastError", "");
    }

    @Test
    void unconfiguredWebhookUsesLogWithoutNetworkTraffic() {
        WebhookNotifier disabled = new WebhookNotifier("", new ObjectMapper(), policy);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.send("failure", "test", 1000)).isTrue();
        assertThat(bodies).isEmpty();
    }

    @Test
    void invalidPolicyFailsAtStartup() {
        policy.setMetadataRetention(policy.getDlqRetention());
        assertThatThrownBy(policy::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
