package com.lingxiao.inventory.application;

import com.lingxiao.inventory.api.dto.ReserveItem;
import com.lingxiao.inventory.api.dto.ReserveResponse;
import com.lingxiao.inventory.infrastructure.db.spanner.InventoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class InventoryAppService {

    private final InventoryRepository repository;
    private final Duration reservationTtl;

    public InventoryAppService(InventoryRepository repository,
                               @Value("${inventory.reservation.ttl:PT15M}") Duration reservationTtl) {
        this.repository = repository;
        this.reservationTtl = reservationTtl;
    }

    public ReserveResponse reserve(String orderId, List<ReserveItem> items) {
        if (items == null || items.size() != 1 || items.getFirst().qty() != 1) {
            throw new IllegalArgumentException("flash sale mode: only 1 item with qty=1 is supported");
        }
        boolean ok = repository.reserveBatch(orderId, items, reservationTtl);
        if (ok) {
            List<String> ids = items.stream().map(ReserveItem::skuId).toList();
            return new ReserveResponse(ids, List.of());
        }
        List<String> ids = items.stream().map(ReserveItem::skuId).toList();
        return new ReserveResponse(List.of(), ids);
    }

    public int commit(String orderId) {
        return repository.commit(orderId);
    }

    public int release(String orderId) {
        return repository.release(orderId);
    }

    public long getAvailable(String skuId) {
        return repository.getAvailable(skuId);
    }

    public void seed(String skuId, long onHand) {
        repository.seed(skuId, onHand);
    }

    public void addOnHand(String skuId, long delta) {
        repository.addOnHand(skuId, delta);
    }

    public void setOnHand(String skuId, long onHand) {
        repository.setOnHand(skuId, onHand);
    }
}

