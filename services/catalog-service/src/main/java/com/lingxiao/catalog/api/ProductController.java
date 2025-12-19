package com.lingxiao.catalog.api;

import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.application.SkuAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final SkuAppService skuAppService;

    public ProductController(SkuAppService skuAppService) {
        this.skuAppService = skuAppService;
    }

    @GetMapping("/{skuId}")
    public ResponseEntity<SkuResponse> get(@PathVariable("skuId") String skuId) {
        return ResponseEntity.ok(skuAppService.get(skuId));
    }

    @PostMapping("/batchGet")
    public ResponseEntity<List<SkuResponse>> batchGet(@RequestBody List<String> skuIds) {
        return ResponseEntity.ok(skuAppService.batchGet(skuIds));
    }
}

