package com.lingxiao.inventory.api;

import com.lingxiao.inventory.api.dto.CommitRequest;
import com.lingxiao.inventory.api.dto.ReleaseRequest;
import com.lingxiao.inventory.api.dto.ReserveRequest;
import com.lingxiao.inventory.api.dto.ReserveResponse;
import com.lingxiao.inventory.api.dto.SeedRequest;
import com.lingxiao.inventory.api.dto.AddOnHandRequest;
import com.lingxiao.inventory.api.dto.SetOnHandRequest;
import com.lingxiao.inventory.application.InventoryAppService;
import com.lingxiao.inventory.application.InventoryAdminService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/inventory")
@Profile("local")
public class InternalInventoryController {

    private final InventoryAppService appService;
    private final InventoryAdminService adminService;

    public InternalInventoryController(InventoryAppService appService,
                                       InventoryAdminService adminService) {
        this.appService = appService;
        this.adminService = adminService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<ReserveResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        return ResponseEntity.ok(appService.reserve(request.orderId(), request.items()));
    }

    @PostMapping("/commit")
    public ResponseEntity<Integer> commit(@Valid @RequestBody CommitRequest request) {
        return ResponseEntity.ok(appService.commit(request.orderId()));
    }

    @PostMapping("/release")
    public ResponseEntity<Integer> release(@Valid @RequestBody ReleaseRequest request) {
        return ResponseEntity.ok(appService.release(request.orderId()));
    }

    @GetMapping("/{skuId}")
    public ResponseEntity<Long> available(@PathVariable("skuId") String skuId) {
        return ResponseEntity.ok(appService.getAvailable(skuId));
    }

    @PostMapping("/seed")
    public ResponseEntity<Void> seed(@Valid @RequestBody SeedRequest request) {
        adminService.seed(request.skuId(), request.onHand());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/onhand/add")
    public ResponseEntity<Void> addOnHand(@Valid @RequestBody AddOnHandRequest request) {
        adminService.addOnHand(request.skuId(), request.delta());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/onhand/{skuId}/set")
    public ResponseEntity<Void> setOnHand(@PathVariable String skuId,
                                          @Valid @RequestBody SetOnHandRequest request) {
        adminService.setOnHand(skuId, request.onHand());
        return ResponseEntity.ok().build();
    }
}

