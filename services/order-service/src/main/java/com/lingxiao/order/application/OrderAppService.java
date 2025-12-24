package com.lingxiao.order.application;

import com.lingxiao.contracts.events.FlashSaleReservedEventV2;
import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderAppService {

    private static final Logger log = LoggerFactory.getLogger(OrderAppService.class);

    private final OrderRepository repository;

    public OrderAppService(OrderRepository repository) {
        this.repository = repository;
    }

    public void handleFlashSaleReservation(FlashSaleReservedEventV2 event) {
        repository.createFromFlashSaleEvent(event);
        log.debug("Handled flash sale reservation eventId={} orderId={} userId={} skuId={}",
                event.eventId(), event.orderId(), event.userId(), event.skuId());
    }
}

