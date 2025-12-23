package com.lingxiao.inventory.api;

import com.lingxiao.inventory.application.FlashSaleAppService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/flashsale")
@Profile("local")
@Validated
public class InternalFlashSaleController {

    private final FlashSaleAppService service;

    public InternalFlashSaleController(FlashSaleAppService service) {
        this.service = service;
    }

    public record FlashSaleRequest(
            @NotBlank String orderId,
            @NotBlank String skuId,
            @NotBlank String userId,
            @Min(1) long qty
    ) {}

    @PostMapping("/reserve")
    public ResponseEntity<Map<String, Object>> reserve(@RequestBody @Validated FlashSaleRequest req) {
        var result = service.reserve(req.orderId(), req.skuId(), req.userId(), req.qty());
        return ResponseEntity.ok(Map.of(
                "success", result.success(),
                "duplicate", result.duplicate(),
                "insufficient", result.insufficient()
        ));
    }
}

