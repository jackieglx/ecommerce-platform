package com.lingxiao.inventory.application;

import com.lingxiao.common.idempotency.DoneAction;
import com.lingxiao.common.idempotency.Idempotent;
import com.lingxiao.common.idempotency.ProcessingAction;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FlashSaleReservationService {

    private final FlashSaleAppService flashSaleAppService;
    private final FlashSalePricingService pricingService;

    public FlashSaleReservationService(FlashSaleAppService flashSaleAppService,
                                       FlashSalePricingService pricingService) {
        this.flashSaleAppService = flashSaleAppService;
        this.pricingService = pricingService;
    }

    @Idempotent(
            eventType = "flashsale_reserve_api_v1",
            id = "#userId + ':' + #idempotencyKey",
            payload = "#skuId + '|' + #qty",
            result = "#result",
            onProcessing = ProcessingAction.RETRY,
            onDone = DoneAction.RETURN_POINTER,
            processingTtl = "PT90S",
            doneTtl = "PT2H",
            keyPrefix = "idem:fs"
    )
    public String reserve(String idempotencyKey, String userId, String skuId, long qty) {
        FlashSalePricingService.Price price = pricingService.fetchPrice(skuId);
        String orderId = generateOrderId();
        FlashSaleAppService.FlashSaleResult res = flashSaleAppService.reserve(
                orderId, skuId, userId, qty, price.priceCents(), price.currency());
        FlashSaleReservationResult.Status status;
        if (res.success()) {
            status = FlashSaleReservationResult.Status.RESERVED;
        } else if (res.duplicate()) {
            status = FlashSaleReservationResult.Status.DUPLICATE;
        } else if (res.insufficient()) {
            status = FlashSaleReservationResult.Status.SOLD_OUT;
        } else {
            status = FlashSaleReservationResult.Status.FAILED;
        }
        FlashSaleReservationResult result = new FlashSaleReservationResult(
                status,
                orderId,
                status == FlashSaleReservationResult.Status.RESERVED ? res.expireAt() : null,
                skuId,
                qty
        );
        return result.toPointer();
    }

    private String generateOrderId() {
        return "o-fs-" + UUID.randomUUID();
    }
}


