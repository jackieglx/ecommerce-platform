package com.lingxiao.search.service;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.bulk.UpdateOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.lingxiao.search.api.dto.Sales7dUpdateRequest;
import com.lingxiao.search.api.dto.Sales7dUpdateResponse;
import com.lingxiao.search.client.CatalogClient;
import com.lingxiao.search.dto.CatalogSkuResponse;
import com.lingxiao.search.es.model.SkuDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * Bulk update sales7d field for multiple SKUs.
     * Uses ES bulk update API with doc to only update the sales7d field without replacing the entire document.
     */
    public Sales7dUpdateResponse bulkUpdateSales7d(Sales7dUpdateRequest request) {
        if (request.updates().isEmpty()) {
            return new Sales7dUpdateResponse(0, 0, 0);
        }

        List<BulkOperation> ops = new ArrayList<>();
        for (Sales7dUpdateRequest.Sales7dUpdate update : request.updates()) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("sales7d", update.sales7d());
            doc.put("sales7dHour", update.sales7dHour());
            doc.put("sales7dUpdatedAt", update.sales7dUpdatedAt().toString());

            UpdateOperation<SkuDocument, Map<String, Object>> updateOp = UpdateOperation.of(u -> u
                    .index(indexName)
                    .id(update.skuId())
                    .action(a -> a.doc(doc).docAsUpsert(false))
            );
            ops.add(BulkOperation.of(b -> b.update(updateOp)));
        }

        int requested = request.updates().size();
        int updated = 0;
        int failed = 0;

        try {
            BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b.operations(ops).refresh(refreshOnWrite ? Refresh.True : Refresh.False)));
            if (resp.errors()) {
                log.warn("Bulk update sales7d finished with errors: {}", resp.items());
            }
            // Count successful and failed updates
            for (var item : resp.items()) {
                if (item.error() != null) {
                    failed++;
                } else {
                    updated++;
                }
            }
        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to bulk update sales7d", e);
            throw new RuntimeException("Failed to bulk update sales7d", e);
        }

        return new Sales7dUpdateResponse(requested, updated, failed);
    }
}

