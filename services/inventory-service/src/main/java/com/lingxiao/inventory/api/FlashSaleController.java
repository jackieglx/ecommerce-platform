package com.lingxiao.inventory.api;

import com.lingxiao.inventory.api.dto.FlashSaleReservationRequest;
import com.lingxiao.inventory.api.dto.FlashSaleReservationResponse;
import com.lingxiao.inventory.application.FlashSaleReservationResult;
import com.lingxiao.inventory.application.FlashSaleReservationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
    private final String testUserId;

    public FlashSaleController(FlashSaleReservationService reservationService,
                               @Value("${inventory.flashsale.test-user-id:test-user}") String testUserId) {
        this.reservationService = reservationService;
        this.testUserId = testUserId;
    }

    @PostMapping("/reservations")
    public ResponseEntity<FlashSaleReservationResponse> reserve(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-User-Id", required = false) String userIdHeader,
            @Valid @RequestBody FlashSaleReservationRequest request) {
        String userId = resolveUserId(userIdHeader);
        String idemKey = requireIdempotencyKey(idempotencyKey);
        String pointer = reservationService.reserve(idemKey, userId, request.skuId(), request.qtyOrDefault());
        FlashSaleReservationResult result = FlashSaleReservationResult.fromPointer(pointer);
        FlashSaleReservationResponse response = new FlashSaleReservationResponse(
                result.status().name(),
                result.orderId(),
                result.reservationExpiresAt()
        );
        return ResponseEntity.ok(response);
    }

    private String resolveUserId(String userIdHeader) {
        if (StringUtils.hasText(userIdHeader)) {
            return userIdHeader;
        }
        if (StringUtils.hasText(testUserId)) {
            return testUserId;
        }
        throw new IllegalArgumentException("userId is required from auth context");
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        return idempotencyKey.trim();
    }
}


