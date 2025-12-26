package com.lingxiao.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class FlashSalePricingService {

    private static final Logger log = LoggerFactory.getLogger(FlashSalePricingService.class);

    private final RestClient restClient;

    public FlashSalePricingService(@Value("${inventory.flashsale.catalog-base-url:${CATALOG_BASE_URL:http://catalog-service:8080}}") String catalogBaseUrl,
                                   RestClient.Builder builder) {
        this.restClient = builder.baseUrl(catalogBaseUrl).build();
    }

    public Price fetchPrice(String skuId) {
        try {
            CatalogSkuResponse resp = restClient.get()
                    .uri("/products/{skuId}", skuId)
                    .retrieve()
                    .body(CatalogSkuResponse.class);
            if (resp == null || resp.priceCents() < 0 || !StringUtils.hasText(resp.currency())) {
                throw new IllegalStateException("Catalog returned invalid price for skuId=" + skuId);
            }
            return new Price(resp.priceCents(), resp.currency());
        } catch (RestClientException ex) {
            log.warn("Failed to fetch price for skuId={}", skuId, ex);
            throw new IllegalStateException("Failed to fetch price for skuId=" + skuId, ex);
        }
    }

    public record Price(long priceCents, String currency) {}

    private record CatalogSkuResponse(String skuId, long priceCents, String currency) {}
}


