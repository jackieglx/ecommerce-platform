package com.lingxiao.inventory.application;

import com.lingxiao.inventory.infrastructure.db.spanner.InventoryRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class InventoryAdminService {

    private final InventoryRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final String stockPrefix;
    private final String hashTag;

    public InventoryAdminService(InventoryRepository repository,
                                 StringRedisTemplate redisTemplate,
                                 @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.stock-prefix:fs:stock:}") String stockPrefix,
                                 @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.hash-tag:fs}") String hashTag) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.stockPrefix = stockPrefix;
        this.hashTag = hashTag;
    }

    public void addOnHand(String skuId, long delta) {
        if (delta <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
        repository.addOnHand(skuId, delta);
        syncRedisStockAdd(skuId, delta);
    }

    public void setOnHand(String skuId, long onHand) {
        repository.setOnHand(skuId, onHand);
        long available = repository.getAvailable(skuId);
        syncRedisStockSet(skuId, available);
    }

    public void seed(String skuId, long onHand) {
        repository.seed(skuId, onHand);
        long available = repository.getAvailable(skuId);
        syncRedisStockSet(skuId, available);
    }

    private void syncRedisStockAdd(String skuId, long delta) {
        if (!StringUtils.hasText(skuId)) return;
        String tag = "{" + hashTag + "}";
        String stockKey = stockPrefix + tag + ":" + skuId;
        redisTemplate.opsForValue().increment(stockKey, delta);
    }

    private void syncRedisStockSet(String skuId, long target) {
        if (!StringUtils.hasText(skuId)) return;
        String tag = "{" + hashTag + "}";
        String stockKey = stockPrefix + tag + ":" + skuId;
        String currentStr = redisTemplate.opsForValue().get(stockKey);
        if (currentStr == null) {
            redisTemplate.opsForValue().set(stockKey, Long.toString(target));
            return;
        }
        try {
            long current = Long.parseLong(currentStr);
            long delta = target - current;
            if (delta != 0) {
                redisTemplate.opsForValue().increment(stockKey, delta);
            }
        } catch (NumberFormatException e) {
            redisTemplate.opsForValue().set(stockKey, Long.toString(target));
        }
    }
}


