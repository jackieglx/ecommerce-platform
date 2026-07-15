package com.lingxiao.order.api;

import com.lingxiao.order.application.OrderNotFoundException;
import com.lingxiao.order.application.OrderQueryService;
import com.lingxiao.order.application.OrderSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderQueryController.class)
@Import({OrderApiExceptionHandler.class, OrderQueryControllerTest.MockConfig.class})
class OrderQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderQueryService orderQueryService;

    @Test
    void returnsOnlyThePublicOrderFields() throws Exception {
        when(orderQueryService.getById("o-1")).thenReturn(new OrderSummary("o-1", "sku-1", "user-1", 2,
                "PENDING_PAYMENT", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z")));

        mockMvc.perform(get("/api/v1/orders/o-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("o-1"))
                .andExpect(jsonPath("$.skuId").value("sku-1"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void returns404ForUnknownOrder() throws Exception {
        when(orderQueryService.getById("missing")).thenThrow(new OrderNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found: missing"));
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        OrderQueryService orderQueryService() {
            return mock(OrderQueryService.class);
        }
    }
}
