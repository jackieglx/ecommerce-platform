package com.lingxiao.sales7d.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.lingxiao.sales7d.model.Sales7dUpdate;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for calling search-service internal API to bulk update sales7d.
 */
public class SearchInternalClient {
    private static final Logger log = LoggerFactory.getLogger(SearchInternalClient.class);

    private final String searchServiceBaseUrl;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SearchInternalClient(String searchServiceBaseUrl) {
        this.searchServiceBaseUrl = searchServiceBaseUrl;
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules(); // For Java 8 time support
    }

    /**
     * Bulk update sales7d for multiple SKUs.
     * Returns the number of successfully updated SKUs.
     */
    public int bulkUpdateSales7d(List<Sales7dUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }

        try {
            String url = searchServiceBaseUrl + "/internal/search/bulkUpdateSales7d";
            HttpPost request = new HttpPost(url);
            request.setHeader("Content-Type", "application/json");

            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            List<Map<String, Object>> updatesList = new ArrayList<>();
            for (Sales7dUpdate update : updates) {
                Map<String, Object> updateMap = new HashMap<>();
                updateMap.put("skuId", update.getSkuId());
                updateMap.put("sales7d", update.getSales7d());
                updateMap.put("sales7dHour", update.getSales7dHour());
                updateMap.put("sales7dUpdatedAt", update.getSales7dUpdatedAt().toString());
                updatesList.add(updateMap);
            }
            requestBody.put("updates", updatesList);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
                String responseBody = response.getEntity() != null 
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) 
                    : "";

                if (statusCode >= 200 && statusCode < 300) {
                    MapType mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                    Map<String, Object> result = objectMapper.readValue(responseBody, mapType);
                    Integer updated = (Integer) result.get("updated");
                    return updated != null ? updated : 0;
                } else {
                    log.warn("Failed to bulk update sales7d statusCode={} response={}", statusCode, responseBody);
                    return 0;
                }
            }
        } catch (IOException | ParseException e) {
            log.error("Exception while calling search-service bulkUpdateSales7d", e);
            return 0;
        }
    }

    public void close() throws IOException {
        httpClient.close();
    }
}

