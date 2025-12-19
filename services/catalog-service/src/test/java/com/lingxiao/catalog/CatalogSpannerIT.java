package com.lingxiao.catalog;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.common.db.config.SpannerProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogSpannerIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SpannerProperties spannerProperties;

    @Test
    void createAndGetSku() {
        Assumptions.assumeTrue(isEmulatorUp(), "Spanner emulator not reachable");

        String skuId = UUID.randomUUID().toString();
        String productId = UUID.randomUUID().toString();
        CreateSkuRequest request = new CreateSkuRequest(
                skuId,
                productId,
                "Test Title",
                "ACTIVE",
                1299,
                "USD"
        );

        String base = "http://localhost:" + port;
        ResponseEntity<SkuResponse> created = rest.postForEntity(base + "/internal/skus", request, SkuResponse.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().skuId()).isEqualTo(skuId);

        ResponseEntity<SkuResponse> fetched = rest.getForEntity(base + "/products/" + skuId, SkuResponse.class);
        assertThat(fetched.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().productId()).isEqualTo(productId);
        assertThat(fetched.getBody().priceCents()).isEqualTo(1299);
    }

    private boolean isEmulatorUp() {
        String host = spannerProperties.getEmulatorHost();
        if (host == null || !host.contains(":")) {
            return false;
        }
        String[] parts = host.split(":");
        String h = parts[0];
        int port = Integer.parseInt(parts[1]);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(h, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

