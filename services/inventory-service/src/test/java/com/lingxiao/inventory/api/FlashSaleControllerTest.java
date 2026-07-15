package com.lingxiao.inventory.api;

import com.lingxiao.inventory.api.dto.FlashSaleReservationRequest;
import com.lingxiao.inventory.api.dto.FlashSaleReservationResponse;
import com.lingxiao.inventory.application.FlashSaleReservationResult;
import com.lingxiao.inventory.application.FlashSaleReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlashSaleControllerTest {

    @Test
    void duplicateResponseHasNoNavigableOrderId() {
        FlashSaleReservationService service = mock(FlashSaleReservationService.class);
        FlashSaleReservationResult duplicate = new FlashSaleReservationResult(
                FlashSaleReservationResult.Status.DUPLICATE, null, null, "demo-phone-001", 1);
        when(service.reserve("idem-duplicate", "demo-user-001", "demo-phone-001", 1))
                .thenReturn(duplicate.toPointer());

        ResponseEntity<FlashSaleReservationResponse> response = new FlashSaleController(service).reserve(
                "idem-duplicate", "demo-user-001", new FlashSaleReservationRequest("demo-phone-001", 1L));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("DUPLICATE");
        assertThat(response.getBody().orderId()).isNull();
    }
}
