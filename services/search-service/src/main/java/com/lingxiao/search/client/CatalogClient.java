package com.lingxiao.search.client;

import com.lingxiao.search.dto.CatalogSkuResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;

@Component
public class CatalogClient {

    private final RestTemplate restTemplate;
    private final String catalogBaseUrl;

    public CatalogClient(RestTemplate restTemplate,
                         @Value("${catalog.base-url:http://localhost:8080}") String catalogBaseUrl) {
        this.restTemplate = restTemplate;
        this.catalogBaseUrl = catalogBaseUrl;
    }

    public List<CatalogSkuResponse> batchGet(List<String> skuIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<List<String>> entity = new HttpEntity<>(skuIds, headers);
        CatalogSkuResponse[] resp = restTemplate.postForObject(
                catalogBaseUrl + "/products/batchGet",
                entity,
                CatalogSkuResponse[].class
        );
        if (resp == null) {
            return List.of();
        }
        return Arrays.asList(resp);
    }
}

