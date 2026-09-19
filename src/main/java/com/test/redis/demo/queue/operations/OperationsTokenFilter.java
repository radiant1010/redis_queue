package com.test.redis.demo.queue.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class OperationsTokenFilter extends OncePerRequestFilter {
    private final String token;

    public OperationsTokenFilter(@Value("${queue.ops.token:}") String token) { this.token = token; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token.isBlank()) {
            response.sendError(503, "Operations API disabled; configure QUEUE_OPS_TOKEN");
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401, "Bearer token required");
            return;
        }
        chain.doFilter(request, response);
    }
}
