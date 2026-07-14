package com.lingxiao.inventory.application;

import com.lingxiao.inventory.infrastructure.db.spanner.InventoryRepository;
import com.lingxiao.inventory.infrastructure.redis.FlashSaleKeyGenerator;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class InventoryAdminService {

    private final InventoryRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final FlashSaleKeyGenerator keyGenerator;
    private final FlashSalePricingService pricingService;

    public InventoryAdminService(InventoryRepository repository,
                                 StringRedisTemplate redisTemplate,
                                 FlashSaleKeyGenerator keyGenerator,
                                 FlashSalePricingService pricingService) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.keyGenerator = keyGenerator;
        this.pricingService = pricingService;
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
        // Preheat price into Redis so the reserve hot path never calls catalog synchronously.
        // This catalog call is off the hot path (once per SKU at activity setup) and must
        // succeed for the SKU to be reservable later (otherwise reserve Lua returns -3).
        preheatPrice(skuId);
    }

    private void preheatPrice(String skuId) {
        if (!StringUtils.hasText(skuId)) return;
        FlashSalePricingService.Price price = pricingService.fetchPrice(skuId);
        String priceKey = keyGenerator.priceKey(skuId);
        redisTemplate.opsForHash().putAll(priceKey, Map.of(
                "priceCents", Long.toString(price.priceCents()),
                "currency", price.currency()
        ));
    }

    private void syncRedisStockAdd(String skuId, long delta) {
        if (!StringUtils.hasText(skuId)) return;
        String stockKey = keyGenerator.stockKey(skuId);
        redisTemplate.opsForValue().increment(stockKey, delta);
    }

    private void syncRedisStockSet(String skuId, long target) {
        if (!StringUtils.hasText(skuId)) return;
        String stockKey = keyGenerator.stockKey(skuId);
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


