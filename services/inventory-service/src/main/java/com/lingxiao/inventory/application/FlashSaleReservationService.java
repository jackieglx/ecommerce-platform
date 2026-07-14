package com.lingxiao.inventory.application;

import com.lingxiao.common.idempotency.DoneAction;
import com.lingxiao.common.idempotency.Idempotent;
import com.lingxiao.common.idempotency.ProcessingAction;
import org.springframework.stereotype.Service;

@Service
public class FlashSaleReservationService {

    private final FlashSaleAppService flashSaleAppService;
    private final com.lingxiao.inventory.infrastructure.redis.FlashSaleKeyGenerator keyGenerator;

    public FlashSaleReservationService(FlashSaleAppService flashSaleAppService,
                                       com.lingxiao.inventory.infrastructure.redis.FlashSaleKeyGenerator keyGenerator) {
        this.flashSaleAppService = flashSaleAppService;
        this.keyGenerator = keyGenerator;
    }

    @Idempotent(
            eventType = "flashsale_reserve_api_v1",
            id = "#p1 + ':' + #p0",          // p0=idempotencyKey, p1=userId
            payload = "#p2 + '|' + #p3",     // p2=skuId, p3=qty
            result = "#result",
            onProcessing = ProcessingAction.RETRY,
            onDone = DoneAction.RETURN_POINTER,
            processingTtl = "PT90S",
            doneTtl = "PT2H",
            keyPrefix = "idem:fs"
    )
    public String reserve(String idempotencyKey, String userId, String skuId, long qty) {
        // Price is preheated into Redis (read inside the reserve Lua); no synchronous
        // catalog call on the hot path. Missing price surfaces as FAILED (Lua returns -3).
        String orderId = keyGenerator.generateOrderIdForSku(skuId);
        FlashSaleAppService.FlashSaleResult res = flashSaleAppService.reserve(
                orderId, skuId, userId, qty);
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
}


