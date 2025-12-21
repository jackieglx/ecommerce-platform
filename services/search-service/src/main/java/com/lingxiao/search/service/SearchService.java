package com.lingxiao.search.service;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import com.lingxiao.search.api.dto.SearchResponseItem;
import com.lingxiao.search.api.dto.SearchResult;
import com.lingxiao.search.es.model.SkuDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class SearchService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ElasticsearchClient esClient;
    private final String indexName;

    public SearchService(ElasticsearchClient esClient,
                         @Value("${search.index:products_v1}") String indexName) {
        this.esClient = esClient;
        this.indexName = indexName;
    }

    public SearchResult search(String q, String brand, Long minPrice, Long maxPrice,
                               String sort, int page, int size) {

        if (page < 1) throw new IllegalArgumentException("page must be >= 1");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        int from = (page - 1) * size;

        // must: keyword query
        List<Query> must = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            must.add(Query.of(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields(List.of("title^3", "title.autocomplete", "productId", "skuId"))
            )));
        }

        // filter: exact filters (no scoring impact)
        List<Query> filters = new ArrayList<>();

        if (brand != null && !brand.isBlank()) {
            String normBrand = brand.trim().toLowerCase(); // IMPORTANT
            filters.add(Query.of(qry -> qry.term(t -> t.field("brand").value(normBrand))));
        }

        if (minPrice != null) {
            filters.add(new Query.Builder()
                    .range(r -> r
                            .untyped(u -> u
                                    .field("priceCents")
                                    .gte(JsonData.of(minPrice))
                            )
                    )
                    .build());
        }

        if (maxPrice != null) {
            filters.add(new Query.Builder()
                    .range(r -> r
                            .untyped(u -> u
                                    .field("priceCents")
                                    .lte(JsonData.of(maxPrice))
                            )
                    )
                    .build());
        }

        BoolQuery.Builder bool = new BoolQuery.Builder();
        if (!must.isEmpty()) bool.must(must);
        if (!filters.isEmpty()) bool.filter(filters);

        List<SortOptions> sorts = resolveSort(sort);

        SearchRequest req = SearchRequest.of(s -> s
                .index(indexName)
                .from(from)
                .size(size)
                .query(bool.build()._toQuery())
                .sort(sorts)
        );

        try {
            SearchResponse<SkuDocument> resp = esClient.search(req, SkuDocument.class);

            List<SearchResponseItem> items = resp.hits().hits().stream()
                    .map(h -> h.source())
                    .filter(Objects::nonNull)
                    .map(this::toResponse)
                    .toList();

            long total = (resp.hits().total() != null)
                    ? resp.hits().total().value()
                    : items.size();

            return new SearchResult(total, items);
        } catch (IOException e) {
            throw new RuntimeException("search failed", e);
        }
    }

    private SearchResponseItem toResponse(SkuDocument doc) {
        return new SearchResponseItem(
                doc.skuId(),
                doc.productId(),
                doc.title(),
                doc.status(),
                doc.brand(),
                doc.priceCents(),
                doc.currency(),
                doc.sales7d(),
                doc.createdAt(),
                doc.updatedAt()
        );
    }

    private List<SortOptions> resolveSort(String sort) {
        if (sort == null || sort.isBlank() || "relevance".equalsIgnoreCase(sort)) {
            return List.of();
        }
        return switch (sort) {
            case "price_asc" -> List.of(SortOptions.of(s -> s.field(f -> f.field("priceCents").order(SortOrder.Asc))));
            case "price_desc" -> List.of(SortOptions.of(s -> s.field(f -> f.field("priceCents").order(SortOrder.Desc))));
            case "sales7d_desc" -> List.of(SortOptions.of(s -> s.field(f -> f.field("sales7d").order(SortOrder.Desc))));
            default -> List.of();
        };
    }
}
