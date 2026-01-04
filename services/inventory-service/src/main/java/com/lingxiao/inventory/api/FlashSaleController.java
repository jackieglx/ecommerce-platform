package com.lingxiao.inventory.api;

import com.lingxiao.inventory.api.dto.FlashSaleReservationRequest;
import com.lingxiao.inventory.api.dto.FlashSaleReservationResponse;
import com.lingxiao.inventory.application.FlashSaleReservationResult;
import com.lingxiao.inventory.application.FlashSaleReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flashsale")
@Validated
public class FlashSaleController {

    private final FlashSaleReservationService reservationService;

    public FlashSaleController(FlashSaleReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/reservations")
    public ResponseEntity<FlashSaleReservationResponse> reserve(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestHeader("X-User-Id") @NotBlank String userId,
            @Valid @RequestBody FlashSaleReservationRequest request) {
        String pointer = reservationService.reserve(idempotencyKey.trim(), userId.trim(), request.skuId(), request.qtyOrDefault());
        FlashSaleReservationResult result = FlashSaleReservationResult.fromPointer(pointer);
        FlashSaleReservationResponse response = new FlashSaleReservationResponse(
                result.status().name(),
                result.orderId(),
                result.reservationExpiresAt()
        );
        return ResponseEntity.ok(response);
    }
}


