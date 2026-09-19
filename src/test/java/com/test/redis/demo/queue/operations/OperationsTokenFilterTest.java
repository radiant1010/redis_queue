package com.test.redis.demo.queue.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class OperationsTokenFilterTest {
    @Test
    void blankTokenDisablesOperationsInsteadOfAllowingAnonymousAccess() throws Exception {
        var filter = new OperationsTokenFilter("");
        var response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("POST", "/ops/jobs/id/retry"), response,
                (request, reply) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(reached.get()).isFalse();
    }
}
