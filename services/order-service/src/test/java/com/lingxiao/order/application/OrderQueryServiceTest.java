package com.lingxiao.order.application;

import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderQueryServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderQueryService service = new OrderQueryService(repository);

    @Test
    void returnsPersistedOrderSummary() {
        OrderSummary summary = new OrderSummary("o-1", "sku-1", "user-1", 2,
                "PENDING_PAYMENT", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z"));
        when(repository.findSummary("o-1")).thenReturn(Optional.of(summary));

        assertEquals(summary, service.getById("o-1"));
    }

    @Test
    void throwsNotFoundForUnknownOrder() {
        when(repository.findSummary("missing")).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> service.getById("missing"));
    }
}
