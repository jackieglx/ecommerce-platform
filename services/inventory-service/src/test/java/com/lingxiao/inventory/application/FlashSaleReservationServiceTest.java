package com.lingxiao.inventory.application;

import com.lingxiao.inventory.infrastructure.redis.FlashSaleKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlashSaleReservationServiceTest {

    private final FlashSaleAppService appService = mock(FlashSaleAppService.class);
    private final FlashSaleKeyGenerator keyGenerator = mock(FlashSaleKeyGenerator.class);
    private FlashSaleReservationService service;

    @BeforeEach
    void setUp() {
        service = new FlashSaleReservationService(appService, keyGenerator);
        when(keyGenerator.generateOrderIdForSku("demo-phone-001")).thenReturn("o-new-123");
    }

    @Test
    void reservedReturnsTheOrderIdThatEntersTheEventChain() {
        Instant expiresAt = Instant.parse("2026-07-15T00:05:00Z");
        when(appService.reserve("o-new-123", "demo-phone-001", "demo-user-001", 1))
                .thenReturn(new FlashSaleAppService.FlashSaleResult(true, false, false, expiresAt));

        FlashSaleReservationResult result = FlashSaleReservationResult.fromPointer(
                service.reserve("idem-1", "demo-user-001", "demo-phone-001", 1));

        assertThat(result.status()).isEqualTo(FlashSaleReservationResult.Status.RESERVED);
        assertThat(result.orderId()).isEqualTo("o-new-123");
        assertThat(result.reservationExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void duplicateDoesNotReturnTheNewOrderIdThatNeverEnteredTheEventChain() {
        when(appService.reserve("o-new-123", "demo-phone-001", "demo-user-001", 1))
                .thenReturn(new FlashSaleAppService.FlashSaleResult(false, true, false, Instant.now()));

        FlashSaleReservationResult result = FlashSaleReservationResult.fromPointer(
                service.reserve("idem-2", "demo-user-001", "demo-phone-001", 1));

        assertThat(result.status()).isEqualTo(FlashSaleReservationResult.Status.DUPLICATE);
        assertThat(result.orderId()).isNull();
        assertThat(result.reservationExpiresAt()).isNull();
    }
}
