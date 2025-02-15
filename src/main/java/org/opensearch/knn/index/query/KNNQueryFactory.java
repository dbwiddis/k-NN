/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.query;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.apache.lucene.search.KnnByteVectorQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.DiversifyingChildrenByteKnnVectorQuery;
import org.apache.lucene.search.join.DiversifyingChildrenFloatKnnVectorQuery;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.knn.index.VectorDataType;
import org.opensearch.knn.index.util.KNNEngine;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

import static org.opensearch.knn.common.KNNConstants.VECTOR_DATA_TYPE_FIELD;
import static org.opensearch.knn.index.VectorDataType.SUPPORTED_VECTOR_DATA_TYPES;

/**
 * Creates the Lucene k-NN queries
 */
@Log4j2
public class KNNQueryFactory {

    /**
     * Creates a Lucene query for a particular engine.
     *
     * @param knnEngine Engine to create the query for
     * @param indexName Name of the OpenSearch index that is being queried
     * @param fieldName Name of the field in the OpenSearch index that will be queried
     * @param vector The query vector to get the nearest neighbors for
     * @param k the number of nearest neighbors to return
     * @return Lucene Query
     */
    public static Query create(
        KNNEngine knnEngine,
        String indexName,
        String fieldName,
        float[] vector,
        int k,
        VectorDataType vectorDataType
    ) {
        final CreateQueryRequest createQueryRequest = CreateQueryRequest.builder()
            .knnEngine(knnEngine)
            .indexName(indexName)
            .fieldName(fieldName)
            .vector(vector)
            .vectorDataType(vectorDataType)
            .k(k)
            .build();
        return create(createQueryRequest);
    }

    /**
     * Creates a Lucene query for a particular engine.
     * @param createQueryRequest request object that has all required fields to construct the query
     * @return Lucene Query
     */
    public static Query create(CreateQueryRequest createQueryRequest) {
        // Engines that create their own custom segment files cannot use the Lucene's KnnVectorQuery. They need to
        // use the custom query type created by the plugin
        final String indexName = createQueryRequest.getIndexName();
        final String fieldName = createQueryRequest.getFieldName();
        final int k = createQueryRequest.getK();
        final float[] vector = createQueryRequest.getVector();
        final byte[] byteVector = createQueryRequest.getByteVector();
        final VectorDataType vectorDataType = createQueryRequest.getVectorDataType();
        final Query filterQuery = getFilterQuery(createQueryRequest);

        if (KNNEngine.getEnginesThatCreateCustomSegmentFiles().contains(createQueryRequest.getKnnEngine())) {
            if (filterQuery != null && KNNEngine.getEnginesThatSupportsFilters().contains(createQueryRequest.getKnnEngine())) {
                log.debug("Creating custom k-NN query with filters for index: {}, field: {} , k: {}", indexName, fieldName, k);
                return new KNNQuery(fieldName, vector, k, indexName, filterQuery);
            }
            log.debug(String.format("Creating custom k-NN query for index: %s \"\", field: %s \"\", k: %d", indexName, fieldName, k));
            return new KNNQuery(fieldName, vector, k, indexName);
        }

        log.debug(String.format("Creating Lucene k-NN query for index: %s \"\", field: %s \"\", k: %d", indexName, fieldName, k));
        BitSetProducer parentFilter = createQueryRequest.context == null ? null : createQueryRequest.context.getParentFilter();
        if (VectorDataType.BYTE == vectorDataType) {
            return getKnnByteVectorQuery(fieldName, byteVector, k, filterQuery, parentFilter);
        } else if (VectorDataType.FLOAT == vectorDataType) {
            return getKnnFloatVectorQuery(fieldName, vector, k, filterQuery, parentFilter);
        } else {
            throw new IllegalArgumentException(
                String.format(
                    Locale.ROOT,
                    "Invalid value provided for [%s] field. Supported values are [%s]",
                    VECTOR_DATA_TYPE_FIELD,
                    SUPPORTED_VECTOR_DATA_TYPES
                )
            );
        }
    }

    /**
     * If parentFilter is not null, it is a nested query. Therefore, we return {@link DiversifyingChildrenByteKnnVectorQuery}
     * which will dedupe search result per parent so that we can get k parent results at the end.
     */
    private static Query getKnnByteVectorQuery(
        final String fieldName,
        final byte[] byteVector,
        final int k,
        final Query filterQuery,
        final BitSetProducer parentFilter
    ) {
        if (parentFilter == null) {
            return new KnnByteVectorQuery(fieldName, byteVector, k, filterQuery);
        } else {
            return new DiversifyingChildrenByteKnnVectorQuery(fieldName, byteVector, filterQuery, k, parentFilter);
        }
    }

    /**
     * If parentFilter is not null, it is a nested query. Therefore, we return {@link DiversifyingChildrenFloatKnnVectorQuery}
     * which will dedupe search result per parent so that we can get k parent results at the end.
     */
    private static Query getKnnFloatVectorQuery(
        final String fieldName,
        final float[] floatVector,
        final int k,
        final Query filterQuery,
        final BitSetProducer parentFilter
    ) {
        if (parentFilter == null) {
            return new KnnFloatVectorQuery(fieldName, floatVector, k, filterQuery);
        } else {
            return new DiversifyingChildrenFloatKnnVectorQuery(fieldName, floatVector, filterQuery, k, parentFilter);
        }
    }

    private static Query getFilterQuery(CreateQueryRequest createQueryRequest) {
        if (createQueryRequest.getFilter().isPresent()) {
            final QueryShardContext queryShardContext = createQueryRequest.getContext()
                .orElseThrow(() -> new RuntimeException("Shard context cannot be null"));
            log.debug(
                String.format(
                    "Creating k-NN query with filter for index [%s], field [%s] and k [%d]",
                    createQueryRequest.getIndexName(),
                    createQueryRequest.fieldName,
                    createQueryRequest.k
                )
            );
            try {
                return createQueryRequest.getFilter().get().toQuery(queryShardContext);
            } catch (IOException e) {
                throw new RuntimeException("Cannot create knn query with filter", e);
            }
        }
        return null;
    }

    /**
     * DTO object to hold data required to create a Query instance.
     */
    @AllArgsConstructor
    @Builder
    @Setter
    static class CreateQueryRequest {
        @Getter
        @NonNull
        private KNNEngine knnEngine;
        @Getter
        @NonNull
        private String indexName;
        @Getter
        private String fieldName;
        @Getter
        private float[] vector;
        @Getter
        private byte[] byteVector;
        @Getter
        private VectorDataType vectorDataType;
        @Getter
        private int k;
        // can be null in cases filter not passed with the knn query
        @Getter
        private BitSetProducer parentFilter;
        private QueryBuilder filter;
        // can be null in cases filter not passed with the knn query
        private QueryShardContext context;

        public Optional<QueryBuilder> getFilter() {
            return Optional.ofNullable(filter);
        }

        public Optional<QueryShardContext> getContext() {
            return Optional.ofNullable(context);
        }
    }
}
