package com.lingxiao.order.messaging;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.FlashSaleReservedEventV2;
import com.lingxiao.order.application.OrderAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FlashSaleReservedConsumer {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleReservedConsumer.class);

    private final OrderAppService appService;

    public FlashSaleReservedConsumer(OrderAppService appService) {
        this.appService = appService;
    }

    @KafkaListener(topics = Topics.FLASH_SALE_RESERVED_V2)
    public void onMessage(FlashSaleReservedEventV2 event) {
        try {
            appService.handleFlashSaleReservation(event);
        } catch (Exception e) {
            log.warn("Failed to handle flash sale reserved event eventId={} orderId={} userId={} skuId={}",
                    event.eventId(), event.orderId(), event.userId(), event.skuId(), e);
            throw e;
        }
    }
}

