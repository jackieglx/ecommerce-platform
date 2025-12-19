package com.lingxiao.common.db.ddl;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DdlResourceLoader {

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public List<String> load(String pattern) {
        try {
            Resource[] resources = resolver.getResources(pattern);
            List<String> statements = new ArrayList<>();
            for (Resource resource : resources) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String sql = reader.lines().collect(Collectors.joining("\n")).trim();
                    if (!sql.isEmpty()) {
                        statements.add(sql);
                    }
                }
            }
            return statements;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load DDL from pattern " + pattern, e);
        }
    }
}

