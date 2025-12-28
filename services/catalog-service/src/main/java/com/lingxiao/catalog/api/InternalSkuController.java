package com.lingxiao.catalog.api;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.api.dto.UpdateSkuRequest;
import com.lingxiao.catalog.application.command.SkuCommandService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/admin/v1/skus")
@Profile("local")
public class InternalSkuController {

    private final SkuCommandService skuCommandService;

    public InternalSkuController(SkuCommandService skuCommandService) {
        this.skuCommandService = skuCommandService;
    }

    @PostMapping
    public ResponseEntity<SkuResponse> create(@Valid @RequestBody CreateSkuRequest request) {
        return ResponseEntity.ok(skuCommandService.create(request));
    }

    @PatchMapping("/{skuId}")
    public ResponseEntity<SkuResponse> update(
            @PathVariable("skuId") String skuId,
            @Valid @RequestBody UpdateSkuRequest request
    ) {
        return ResponseEntity.ok(skuCommandService.update(skuId, request));
    }

}

