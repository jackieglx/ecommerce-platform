package com.lingxiao.catalog.api;

import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.application.query.SkuQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/skus")
public class SkuController {

    private final SkuQueryService skuQueryService;

    public SkuController(SkuQueryService skuQueryService) {
        this.skuQueryService = skuQueryService;
    }

    @GetMapping("/{skuId}")
    public ResponseEntity<SkuResponse> get(@PathVariable("skuId") String skuId) {
        return ResponseEntity.ok(skuQueryService.get(skuId));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<SkuResponse>> batchGet(@RequestBody List<String> skuIds) {
        return ResponseEntity.ok(skuQueryService.batchGet(skuIds));
    }
}

