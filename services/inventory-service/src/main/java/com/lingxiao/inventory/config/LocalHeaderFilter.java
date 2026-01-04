package com.lingxiao.inventory.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Local profile filter to automatically fill missing headers for testing convenience.
 * Only applies to /api/v1/flashsale/** paths.
 * - If X-User-Id is missing or blank, fills with "test-user"
 * - If Idempotency-Key is missing or blank, generates a UUID
 */
@Component
@Profile("local")
public class LocalHeaderFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String DEFAULT_USER_ID = "test-user";
    private static final String FLASH_SALE_PATH_PREFIX = "/api/v1/flashsale/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(FLASH_SALE_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        HeaderRequestWrapper wrappedRequest = new HeaderRequestWrapper(request);

        // Fill X-User-Id if missing or blank
        if (!wrappedRequest.hasHeader(USER_ID_HEADER)) {
            wrappedRequest.addHeader(USER_ID_HEADER, DEFAULT_USER_ID);
        }

        // Fill Idempotency-Key if missing or blank
        if (!wrappedRequest.hasHeader(IDEMPOTENCY_KEY_HEADER)) {
            wrappedRequest.addHeader(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString());
        }

        filterChain.doFilter(wrappedRequest, response);
    }
}

