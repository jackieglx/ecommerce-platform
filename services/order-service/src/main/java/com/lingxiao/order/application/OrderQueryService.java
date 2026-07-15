package com.lingxiao.order.application;

import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderQueryService {

    private final OrderRepository repository;

    public OrderQueryService(OrderRepository repository) {
        this.repository = repository;
    }

    public OrderSummary getById(String orderId) {
        return repository.findSummary(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
