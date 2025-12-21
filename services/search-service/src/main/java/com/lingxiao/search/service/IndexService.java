package com.lingxiao.search.service;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.lingxiao.search.client.CatalogClient;
import com.lingxiao.search.dto.CatalogSkuResponse;
import com.lingxiao.search.es.model.SkuDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final ElasticsearchClient esClient;
    private final CatalogClient catalogClient;
    private final String indexName;
    private final boolean refreshOnWrite;

    public IndexService(ElasticsearchClient esClient,
                        CatalogClient catalogClient,
                        @Value("${search.index:products_v1}") String indexName,
                        @Value("${search.index.refresh:true}") boolean refreshOnWrite) {
        this.esClient = esClient;
        this.catalogClient = catalogClient;
        this.indexName = indexName;
        this.refreshOnWrite = refreshOnWrite;
    }

    public IndexResult indexSkus(List<String> skuIds) {
        List<CatalogSkuResponse> skus = catalogClient.batchGet(skuIds);
        if (skus.isEmpty()) {
            return new IndexResult(skuIds.size(), 0, skuIds);
        }

        List<BulkOperation> ops = new ArrayList<>();
        for (CatalogSkuResponse sku : skus) {
            SkuDocument doc = new SkuDocument(
                    sku.skuId(),
                    sku.productId(),
                    sku.title(),
                    sku.status(),
                    sku.brand(),
                    sku.priceCents(),
                    sku.currency(),
                    0L,
                    sku.createdAt(),
                    sku.updatedAt()
            );
            IndexOperation<SkuDocument> idx = IndexOperation.of(b -> b
                    .index(indexName)
                    .id(doc.skuId())
                    .document(doc)
            );
            ops.add(BulkOperation.of(b -> b.index(idx)));
        }

        try {
            BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b.operations(ops).refresh(refreshOnWrite ? Refresh.True : Refresh.False)));
            if (resp.errors()) {
                log.warn("Bulk index finished with errors: {}", resp.items());
            }
        } catch (ElasticsearchException | IOException e) {
            throw new RuntimeException("failed to bulk index", e);
        }
        Set<String> foundIds = new HashSet<>(skus.stream().map(CatalogSkuResponse::skuId).toList());
        List<String> missing = new ArrayList<>();
        for (String id : skuIds) {
            if (!foundIds.contains(id)) {
                missing.add(id);
            }
        }
        return new IndexResult(skuIds.size(), ops.size(), missing);
    }
}

