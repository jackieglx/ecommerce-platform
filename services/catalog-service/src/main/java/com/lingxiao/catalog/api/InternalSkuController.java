package com.lingxiao.catalog.api;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.application.SkuAppService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/skus")
@Profile("local")
public class InternalSkuController {

    private final SkuAppService skuAppService;

    public InternalSkuController(SkuAppService skuAppService) {
        this.skuAppService = skuAppService;
    }

    @PostMapping
    public ResponseEntity<SkuResponse> create(@Valid @RequestBody CreateSkuRequest request) {
        return ResponseEntity.ok(skuAppService.create(request));
    }
}

