package com.lingxiao.search.api;

import com.lingxiao.search.api.dto.IndexResponse;
import com.lingxiao.search.dto.IndexRequest;
import com.lingxiao.search.service.IndexResult;
import com.lingxiao.search.service.IndexService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/search")
@Profile("local")
public class InternalIndexController {

    private final IndexService indexService;

    public InternalIndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    @PostMapping("/indexSkus")
    public ResponseEntity<IndexResponse> indexSkus(@Valid @RequestBody IndexRequest request) {
        IndexResult result = indexService.indexSkus(request.skuIds());
        return ResponseEntity.ok(new IndexResponse(result.requested(), result.indexed(), result.missing()));
    }
}

