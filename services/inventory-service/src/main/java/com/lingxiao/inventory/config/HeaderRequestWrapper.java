package com.lingxiao.inventory.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Request wrapper that allows adding headers while preserving original header name casing.
 * Uses case-insensitive map internally but preserves the original header names.
 */
public class HeaderRequestWrapper extends HttpServletRequestWrapper {

    private final Map<String, String> headerMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public HeaderRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    public void addHeader(String name, String value) {
        // Store with original case, but map is case-insensitive for lookup
        headerMap.put(name, value != null ? value.trim() : null);
    }

    @Override
    public String getHeader(String name) {
        String headerValue = headerMap.get(name);
        if (headerValue != null) {
            return headerValue;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
        // Add our headers with their original casing preserved
        names.addAll(headerMap.keySet());
        return Collections.enumeration(names);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String headerValue = headerMap.get(name);
        if (headerValue != null) {
            return Collections.enumeration(Collections.singletonList(headerValue));
        }
        return super.getHeaders(name);
    }

    /**
     * Check if header exists and has non-blank value.
     * Uses case-insensitive lookup.
     */
    public boolean hasHeader(String name) {
        return headerMap.containsKey(name) || StringUtils.hasText(super.getHeader(name));
    }
}

