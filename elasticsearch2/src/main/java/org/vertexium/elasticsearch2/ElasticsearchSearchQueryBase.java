package org.vertexium.elasticsearch2;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.*;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.geogrid.GeoHashGridBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.HistogramBuilder;
import org.elasticsearch.search.aggregations.bucket.range.RangeBuilder;
import org.elasticsearch.search.aggregations.bucket.range.date.DateRangeBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.aggregations.metrics.percentiles.PercentilesBuilder;
import org.elasticsearch.search.aggregations.metrics.stats.extended.ExtendedStatsBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.joda.time.DateTime;
import org.vertexium.*;
import org.vertexium.elasticsearch2.score.ScoringStrategy;
import org.vertexium.elasticsearch2.utils.ElasticsearchExtendedDataIdUtils;
import org.vertexium.elasticsearch2.utils.PagingIterable;
import org.vertexium.query.*;
import org.vertexium.type.GeoCircle;
import org.vertexium.type.GeoHash;
import org.vertexium.type.GeoPoint;
import org.vertexium.type.GeoRect;
import org.vertexium.util.IterableUtils;
import org.vertexium.util.JoinIterable;
import org.vertexium.util.VertexiumLogger;
import org.vertexium.util.VertexiumLoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public class ElasticsearchSearchQueryBase extends QueryBase implements
        GraphQueryWithHistogramAggregation,
        GraphQueryWithTermsAggregation,
        GraphQueryWithGeohashAggregation,
        GraphQueryWithStatisticsAggregation {
    private static final VertexiumLogger LOGGER = VertexiumLoggerFactory.getLogger(ElasticsearchSearchQueryBase.class);
    public static final VertexiumLogger QUERY_LOGGER = VertexiumLoggerFactory.getQueryLogger(Query.class);
    private final Client client;
    private final boolean evaluateHasContainers;
    private final boolean evaluateQueryString;
    private final boolean evaluateSortContainers;
    private final StandardAnalyzer analyzer;
    private final ScoringStrategy scoringStrategy;
    private final IndexSelectionStrategy indexSelectionStrategy;
    private final int pageSize;

    public ElasticsearchSearchQueryBase(
            Client client,
            Graph graph,
            String queryString,
            ScoringStrategy scoringStrategy,
            IndexSelectionStrategy indexSelectionStrategy,
            int pageSize,
            Authorizations authorizations
    ) {
        super(graph, queryString, authorizations);
        this.client = client;
        this.evaluateQueryString = false;
        this.evaluateHasContainers = true;
        this.evaluateSortContainers = false;
        this.pageSize = pageSize;
        this.scoringStrategy = scoringStrategy;
        this.analyzer = new StandardAnalyzer();
        this.indexSelectionStrategy = indexSelectionStrategy;
    }

    public ElasticsearchSearchQueryBase(
            Client client,
            Graph graph,
            String[] similarToFields,
            String similarToText,
            ScoringStrategy scoringStrategy,
            IndexSelectionStrategy indexSelectionStrategy,
            int pageSize,
            Authorizations authorizations
    ) {
        super(graph, similarToFields, similarToText, authorizations);
        this.client = client;
        this.evaluateQueryString = false;
        this.evaluateHasContainers = true;
        this.evaluateSortContainers = false;
        this.pageSize = pageSize;
        this.scoringStrategy = scoringStrategy;
        this.analyzer = new StandardAnalyzer();
        this.indexSelectionStrategy = indexSelectionStrategy;
    }

    @Override
    @Deprecated
    public GraphQueryWithHistogramAggregation addHistogramAggregation(String aggregationName, String fieldName, String interval, Long minDocumentCount) {
        addAggregation(new HistogramAggregation(aggregationName, fieldName, interval, minDocumentCount));
        return this;
    }

    @Override
    @Deprecated
    public GraphQueryWithHistogramAggregation addHistogramAggregation(String aggregationName, String fieldName, String interval) {
        return addHistogramAggregation(aggregationName, fieldName, interval, null);
    }

    @Override
    @Deprecated
    public GraphQueryWithTermsAggregation addTermsAggregation(String aggregationName, String fieldName) {
        addAggregation(new TermsAggregation(aggregationName, fieldName));
        return this;
    }

    @Override
    @Deprecated
    public GraphQueryWithGeohashAggregation addGeohashAggregation(String aggregationName, String fieldName, int precision) {
        addAggregation(new GeohashAggregation(aggregationName, fieldName, precision));
        return this;
    }

    @Override
    @Deprecated
    public GraphQueryWithStatisticsAggregation addStatisticsAggregation(String aggregationName, String field) {
        addAggregation(new StatisticsAggregation(aggregationName, field));
        return this;
    }

    @Override
    public boolean isAggregationSupported(Aggregation agg) {
        if (agg instanceof HistogramAggregation) {
            return true;
        }
        if (agg instanceof RangeAggregation) {
            return true;
        }
        if (agg instanceof PercentilesAggregation) {
            return true;
        }
        if (agg instanceof TermsAggregation) {
            return true;
        }
        if (agg instanceof GeohashAggregation) {
            return true;
        }
        if (agg instanceof StatisticsAggregation) {
            return true;
        }
        if (agg instanceof CalendarFieldAggregation) {
            return true;
        }
        return false;
    }

    protected SearchRequestBuilder getSearchRequestBuilder(
            List<QueryBuilder> filters,
            QueryBuilder queryBuilder,
            EnumSet<ElasticsearchDocumentType> elementType,
            int skip,
            int limit,
            boolean includeAggregations
    ) {
        AndQueryBuilder filterBuilder = getFilterBuilder(filters);
        String[] indicesToQuery = getIndexSelectionStrategy().getIndicesToQuery(this, elementType);
        if (QUERY_LOGGER.isTraceEnabled()) {
            QUERY_LOGGER.trace("indicesToQuery: %s", Joiner.on(", ").join(indicesToQuery));
        }
        SearchRequestBuilder searchRequestBuilder = getClient()
                .prepareSearch(indicesToQuery)
                .setTypes(Elasticsearch2SearchIndex.ELEMENT_TYPE)
                .setQuery(QueryBuilders.filteredQuery(queryBuilder, filterBuilder))
                .addField(Elasticsearch2SearchIndex.ELEMENT_TYPE_FIELD_NAME)
                .addField(Elasticsearch2SearchIndex.EXTENDED_DATA_ELEMENT_ID_FIELD_NAME)
                .addField(Elasticsearch2SearchIndex.EXTENDED_DATA_TABLE_NAME_FIELD_NAME)
                .addField(Elasticsearch2SearchIndex.EXTENDED_DATA_TABLE_ROW_ID_FIELD_NAME)
                .setFrom(skip)
                .setSize(limit);
        if (includeAggregations) {
            List<AbstractAggregationBuilder> aggs = getElasticsearchAggregations(getAggregations());
            for (AbstractAggregationBuilder aggregationBuilder : aggs) {
                searchRequestBuilder.addAggregation(aggregationBuilder);
            }
        }
        return searchRequestBuilder;
    }

    protected QueryBuilder createQueryStringQuery(QueryStringQueryParameters queryParameters) {
        String queryString = queryParameters.getQueryString();
        if (queryString == null || queryString.equals("*")) {
            return QueryBuilders.matchAllQuery();
        }
        Elasticsearch2SearchIndex es = (Elasticsearch2SearchIndex) ((GraphWithSearchIndex) getGraph()).getSearchIndex();
        if (es.isServerPluginInstalled()) {
            return VertexiumQueryStringQueryBuilder.build(queryString, getParameters().getAuthorizations());
        } else {
            Collection<String> fields = es.getQueryablePropertyNames(getGraph(), getParameters().getAuthorizations());
            QueryStringQueryBuilder qs = QueryBuilders.queryStringQuery(queryString);
            for (String field : fields) {
                qs = qs.field(field);
            }
            return qs;
        }
    }

    protected List<QueryBuilder> getFilters(EnumSet<ElasticsearchDocumentType> elementTypes) {
        List<QueryBuilder> filters = new ArrayList<>();
        if (elementTypes != null) {
            addElementTypeFilter(filters, elementTypes);
        }
        for (HasContainer has : getParameters().getHasContainers()) {
            if (has instanceof HasValueContainer) {
                filters.add(getFiltersForHasValueContainer((HasValueContainer) has));
            } else if (has instanceof HasPropertyContainer) {
                filters.add(getFilterForHasPropertyContainer((HasPropertyContainer) has));
            } else if (has instanceof HasNotPropertyContainer) {
                filters.add(getFilterForHasNotPropertyContainer((HasNotPropertyContainer) has));
            } else if (has instanceof HasExtendedData) {
                filters.add(getFilterForHasExtendedData((HasExtendedData) has));
            } else {
                throw new VertexiumException("Unexpected type " + has.getClass().getName());
            }
        }
        if ((elementTypes == null || elementTypes.contains(ElasticsearchDocumentType.EDGE))
                && getParameters().getEdgeLabels().size() > 0) {
            String[] edgeLabelsArray = getParameters().getEdgeLabels().toArray(new String[getParameters().getEdgeLabels().size()]);
            filters.add(QueryBuilders.termsQuery(Elasticsearch2SearchIndex.EDGE_LABEL_FIELD_NAME, edgeLabelsArray));
        }

        if (getParameters() instanceof QueryStringQueryParameters) {
            String queryString = ((QueryStringQueryParameters) getParameters()).getQueryString();
            if (queryString == null || queryString.equals("*")) {
                Elasticsearch2SearchIndex es = (Elasticsearch2SearchIndex) ((GraphWithSearchIndex) getGraph()).getSearchIndex();
                Collection<String> fields = es.getQueryableElementTypeVisibilityPropertyNames(getGraph(), getParameters().getAuthorizations());
                OrQueryBuilder atLeastOneFieldExistsFilter = new OrQueryBuilder();
                for (String field : fields) {
                    atLeastOneFieldExistsFilter.add(new ExistsQueryBuilder(field));
                }
                filters.add(atLeastOneFieldExistsFilter);
            }
        }
        return filters;
    }

    protected void applySort(SearchRequestBuilder q) {
        for (SortContainer sortContainer : getParameters().getSortContainers()) {
            SortOrder esOrder = sortContainer.direction == SortDirection.ASCENDING ? SortOrder.ASC : SortOrder.DESC;
            if (Element.ID_PROPERTY_NAME.equals(sortContainer.propertyName)) {
                q.addSort("_uid", esOrder);
            } else if (Edge.LABEL_PROPERTY_NAME.equals(sortContainer.propertyName)) {
                q.addSort(Elasticsearch2SearchIndex.EDGE_LABEL_FIELD_NAME, esOrder);
            } else {
                PropertyDefinition propertyDefinition = getGraph().getPropertyDefinition(sortContainer.propertyName);
                if (propertyDefinition == null) {
                    continue;
                }
                if (!getSearchIndex().isPropertyInIndex(getGraph(), sortContainer.propertyName)) {
                    continue;
                }
                if (!propertyDefinition.isSortable()) {
                    throw new VertexiumException("Cannot sort on non-sortable fields");
                }
                q.addSort(propertyDefinition.getPropertyName() + Elasticsearch2SearchIndex.SORT_PROPERTY_NAME_SUFFIX, esOrder);
            }
        }
    }

    @Override
    public QueryResultsIterable<? extends VertexiumObject> search(EnumSet<VertexiumObjectType> objectTypes, EnumSet<FetchHint> fetchHints) {
        return new PagingIterable<VertexiumObject>(getParameters().getSkip(), getParameters().getLimit(), pageSize) {
            @Override
            protected ElasticsearchGraphQueryIterable<VertexiumObject> getPageIterable(int skip, int limit, boolean includeAggregations) {
                SearchResponse response;
                try {
                    response = getSearchResponse(ElasticsearchDocumentType.fromVertexiumObjectTypes(objectTypes), skip, limit, includeAggregations);
                } catch (IndexNotFoundException ex) {
                    LOGGER.debug("Index missing: %s (returning empty iterable)", ex.getMessage());
                    return createEmptyIterable();
                } catch (VertexiumNoMatchingPropertiesException ex) {
                    LOGGER.debug("Could not find property: %s (returning empty iterable)", ex.getPropertyName());
                    return createEmptyIterable();
                }
                final SearchHits hits = response.getHits();
                Ids ids = new Ids(hits);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "elasticsearch results (vertices: %d + edges: %d + extended data: %d = %d)",
                            ids.getVertexIds().size(),
                            ids.getEdgeIds().size(),
                            ids.getExtendedDataIds().size(),
                            ids.getVertexIds().size() + ids.getEdgeIds().size() + ids.getExtendedDataIds().size()
                    );
                }

                // since ES doesn't support security we will rely on the graph to provide edge filtering
                // and rely on the DefaultGraphQueryIterable to provide property filtering
                QueryParameters filterParameters = getParameters().clone();
                filterParameters.setSkip(0); // ES already did a skip
                List<Iterable<? extends VertexiumObject>> items = new ArrayList<>();
                if (ids.getVertexIds().size() > 0) {
                    Iterable<? extends VertexiumObject> vertices = getGraph().getVertices(ids.getVertexIds(), fetchHints, filterParameters.getAuthorizations());
                    items.add(vertices);
                }
                if (ids.getEdgeIds().size() > 0) {
                    Iterable<? extends VertexiumObject> edges = getGraph().getEdges(ids.getEdgeIds(), fetchHints, filterParameters.getAuthorizations());
                    items.add(edges);
                }
                if (ids.getExtendedDataIds().size() > 0) {
                    Iterable<? extends VertexiumObject> extendedDataRows = getGraph().getExtendedData(ids.getExtendedDataIds(), filterParameters.getAuthorizations());
                    items.add(extendedDataRows);
                }
                Iterable<VertexiumObject> vertexiumObjects = new JoinIterable<>(items);
                vertexiumObjects = sortVertexiumObjectsByResultOrder(vertexiumObjects, ids.getIds());

                boolean shouldEvaluateHas = evaluateHasContainers && (fetchHints.contains(FetchHint.PROPERTIES) || fetchHints.contains(FetchHint.EXTENDED_DATA_TABLE_NAMES));
                // TODO instead of passing false here to not evaluate the query string it would be better to support the Lucene query
                return createIterable(response, filterParameters, vertexiumObjects, evaluateQueryString, shouldEvaluateHas, evaluateSortContainers, response.getTookInMillis(), hits);
            }
        };
    }

    public PagingIterable<SearchHit> search(EnumSet<VertexiumObjectType> objectTypes) {
        return new PagingIterable<SearchHit>(getParameters().getSkip(), getParameters().getLimit(), pageSize) {
            @Override
            protected ElasticsearchGraphQueryIterable<SearchHit> getPageIterable(int skip, int limit, boolean includeAggregations) {
                SearchResponse response;
                try {
                    response = getSearchResponse(ElasticsearchDocumentType.fromVertexiumObjectTypes(objectTypes), skip, limit, includeAggregations);
                } catch (IndexNotFoundException ex) {
                    LOGGER.debug("Index missing: %s (returning empty iterable)", ex.getMessage());
                    return createEmptyIterable();
                } catch (VertexiumNoMatchingPropertiesException ex) {
                    LOGGER.debug("Could not find property: %s (returning empty iterable)", ex.getPropertyName());
                    return createEmptyIterable();
                }

                SearchHits hits = response.getHits();
                QueryParameters filterParameters = getParameters().clone();
                Iterable<SearchHit> hitsIterable = IterableUtils.toIterable(hits.hits());
                return createIterable(response, filterParameters, hitsIterable, false, false, false, response.getTookInMillis(), hits);
            }
        };
    }

    @Override
    public QueryResultsIterable<String> vertexIds() {
        return new ElasticsearchGraphQueryIdIterable<>(search(EnumSet.of(VertexiumObjectType.VERTEX)));
    }

    @Override
    public QueryResultsIterable<String> edgeIds() {
        return new ElasticsearchGraphQueryIdIterable<>(search(EnumSet.of(VertexiumObjectType.EDGE)));
    }

    @Override
    public QueryResultsIterable<ExtendedDataRowId> extendedDataRowIds() {
        return new ElasticsearchGraphQueryIdIterable<>(search(EnumSet.of(VertexiumObjectType.EXTENDED_DATA)));
    }

    @Override
    public QueryResultsIterable<String> elementIds() {
        return new ElasticsearchGraphQueryIdIterable<>(search(VertexiumObjectType.ELEMENTS));
    }

    private <T extends VertexiumObject> Iterable<T> sortVertexiumObjectsByResultOrder(Iterable<T> vertexiumObjects, List<String> ids) {
        ImmutableMap<String, T> itemMap = Maps.uniqueIndex(vertexiumObjects, vertexiumObject -> {
            if (vertexiumObject instanceof Element) {
                return ((Element) vertexiumObject).getId();
            } else if (vertexiumObject instanceof ExtendedDataRow) {
                return ElasticsearchExtendedDataIdUtils.toDocId(((ExtendedDataRow) vertexiumObject).getId());
            } else {
                throw new VertexiumException("Unhandled searchable item type: " + vertexiumObject.getClass().getName());
            }
        });

        List<T> results = new ArrayList<>();
        for (String id : ids) {
            T item = itemMap.get(id);
            if (item != null) {
                results.add(item);
            }
        }
        return results;
    }

    private <T> EmptyElasticsearchGraphQueryIterable<T> createEmptyIterable() {
        return new EmptyElasticsearchGraphQueryIterable<>(ElasticsearchSearchQueryBase.this, getParameters());
    }

    protected <T> ElasticsearchGraphQueryIterable<T> createIterable(
            SearchResponse response,
            QueryParameters filterParameters,
            Iterable<T> vertexiumObjects,
            boolean evaluateQueryString,
            boolean evaluateHasContainers,
            boolean evaluateSortContainers,
            long searchTimeInMillis,
            SearchHits hits
    ) {
        return new ElasticsearchGraphQueryIterable<>(
                this,
                response,
                filterParameters,
                vertexiumObjects,
                evaluateQueryString,
                evaluateHasContainers,
                evaluateSortContainers,
                hits.getTotalHits(),
                searchTimeInMillis * 1000000,
                hits
        );
    }

    private SearchResponse getSearchResponse(EnumSet<ElasticsearchDocumentType> elementType, int skip, int limit, boolean includeAggregations) {
        if (QUERY_LOGGER.isTraceEnabled()) {
            QUERY_LOGGER.trace("searching for: " + toString());
        }
        List<QueryBuilder> filters = getFilters(elementType);
        QueryBuilder query = createQuery(getParameters());
        query = scoringStrategy.updateQuery(query);
        SearchRequestBuilder q = getSearchRequestBuilder(filters, query, elementType, skip, limit, includeAggregations);
        applySort(q);

        if (QUERY_LOGGER.isTraceEnabled()) {
            QUERY_LOGGER.trace("query: %s", q);
        }

        SearchResponse searchResponse = q.execute().actionGet();
        SearchHits hits = searchResponse.getHits();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "elasticsearch results %d of %d (time: %dms)",
                    hits.hits().length,
                    hits.getTotalHits(),
                    searchResponse.getTookInMillis()
            );
        }
        return searchResponse;
    }

    protected QueryBuilder getFilterForHasNotPropertyContainer(HasNotPropertyContainer hasNotProperty) {
        String[] propertyNames;
        try {
            propertyNames = getPropertyNames(hasNotProperty.getKey());
            if (propertyNames.length == 0) {
                throw new VertexiumNoMatchingPropertiesException(hasNotProperty.getKey());
            }
        } catch (VertexiumNoMatchingPropertiesException ex) {
            // If we can't find a property this means it doesn't exist on any elements so the hasNot query should
            // match all records.
            return QueryBuilders.matchAllQuery();
        }
        PropertyDefinition propDef = getPropertyDefinition(hasNotProperty.getKey());
        List<QueryBuilder> filters = new ArrayList<>();
        for (String propertyName : propertyNames) {
            filters.add(QueryBuilders.notQuery(QueryBuilders.existsQuery(propertyName)));
            if (propDef.getDataType().equals(GeoPoint.class)) {
                filters.add(QueryBuilders.notQuery(QueryBuilders.existsQuery(propertyName + Elasticsearch2SearchIndex.GEO_PROPERTY_NAME_SUFFIX)));
            } else if (isExactMatchPropertyDefinition(propDef)) {
                filters.add(QueryBuilders.notQuery(QueryBuilders.existsQuery(propertyName + Elasticsearch2SearchIndex.EXACT_MATCH_PROPERTY_NAME_SUFFIX)));
            }
        }
        return getSingleFilterOrAndTheFilters(filters, hasNotProperty);
    }

    private QueryBuilder getFilterForHasExtendedData(HasExtendedData has) {
        List<QueryBuilder> filters = new ArrayList<>();
        for (HasExtendedDataFilter hasExtendedDataFilter : has.getFilters()) {
            filters.add(getFilterForHasExtendedDataFilter(hasExtendedDataFilter));
        }
        return QueryBuilders.orQuery(filters.toArray(new QueryBuilder[filters.size()]));
    }

    private QueryBuilder getFilterForHasExtendedDataFilter(HasExtendedDataFilter has) {
        List<QueryBuilder> filters = new ArrayList<>();
        if (has.getElementType() != null) {
            filters.add(QueryBuilders.termQuery(
                    Elasticsearch2SearchIndex.ELEMENT_TYPE_FIELD_NAME,
                    ElasticsearchDocumentType.getExtendedDataDocumentTypeFromElementType(has.getElementType()).getKey()
            ));
        }
        if (has.getElementId() != null) {
            filters.add(QueryBuilders.termQuery(Elasticsearch2SearchIndex.EXTENDED_DATA_ELEMENT_ID_FIELD_NAME, has.getElementId()));
        }
        if (has.getTableName() != null) {
            filters.add(QueryBuilders.termQuery(Elasticsearch2SearchIndex.EXTENDED_DATA_TABLE_NAME_FIELD_NAME, has.getTableName()));
        }
        if (filters.size() == 0) {
            throw new VertexiumException("Cannot include a hasExtendedData clause with all nulls");
        }
        return QueryBuilders.andQuery(filters.toArray(new QueryBuilder[filters.size()]));
    }

    protected QueryBuilder getFilterForHasPropertyContainer(HasPropertyContainer hasProperty) {
        String[] propertyNames = getPropertyNames(hasProperty.getKey());
        if (propertyNames.length == 0) {
            throw new VertexiumNoMatchingPropertiesException(hasProperty.getKey());
        }
        PropertyDefinition propDef = getPropertyDefinition(hasProperty.getKey());
        if (propDef == null) {
            throw new VertexiumException("Could not find property definition for property name: " + hasProperty.getKey());
        }
        List<QueryBuilder> filters = new ArrayList<>();
        for (String propertyName : propertyNames) {
            filters.add(QueryBuilders.existsQuery(propertyName));
            if (propDef.getDataType().equals(GeoPoint.class)) {
                filters.add(QueryBuilders.existsQuery(propertyName + Elasticsearch2SearchIndex.GEO_PROPERTY_NAME_SUFFIX));
            } else if (isExactMatchPropertyDefinition(propDef)) {
                filters.add(QueryBuilders.existsQuery(propertyName + Elasticsearch2SearchIndex.EXACT_MATCH_PROPERTY_NAME_SUFFIX));
            }
        }
        return getSingleFilterOrOrTheFilters(filters, hasProperty);
    }

    protected QueryBuilder getFiltersForHasValueContainer(HasValueContainer has) {
        if (has.predicate instanceof Compare) {
            return getFilterForComparePredicate((Compare) has.predicate, has);
        } else if (has.predicate instanceof Contains) {
            return getFilterForContainsPredicate((Contains) has.predicate, has);
        } else if (has.predicate instanceof TextPredicate) {
            return getFilterForTextPredicate((TextPredicate) has.predicate, has);
        } else if (has.predicate instanceof GeoCompare) {
            return getFilterForGeoComparePredicate((GeoCompare) has.predicate, has);
        } else {
            throw new VertexiumException("Unexpected predicate type " + has.predicate.getClass().getName());
        }
    }

    protected QueryBuilder getFilterForGeoComparePredicate(GeoCompare compare, HasValueContainer has) {
        String[] keys = getPropertyNames(has.key);
        if (keys.length == 0) {
            throw new VertexiumNoMatchingPropertiesException(has.key);
        }
        List<QueryBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            String propertyName = key + Elasticsearch2SearchIndex.GEO_PROPERTY_NAME_SUFFIX;
            switch (compare) {
                case WITHIN:
                    Object value = has.value;
                    if (value instanceof GeoHash) {
                        value = ((GeoHash) value).toGeoRect();
                    }

                    if (value instanceof GeoCircle) {
                        GeoCircle geoCircle = (GeoCircle) value;
                        double lat = geoCircle.getLatitude();
                        double lon = geoCircle.getLongitude();
                        double distance = geoCircle.getRadius();

                        String inflatedPropertyName = getSearchIndex().inflatePropertyName(propertyName);
                        PropertyDefinition propertyDefinition = getGraph().getPropertyDefinition(inflatedPropertyName);
                        if (propertyDefinition != null && propertyDefinition.getDataType() == GeoCircle.class) {
                            ShapeBuilder shapeBuilder = ShapeBuilder.newCircleBuilder()
                                    .center(lon, lat)
                                    .radius(distance, DistanceUnit.KILOMETERS);
                            filters
                                    .add(new GeoShapeQueryBuilder(propertyName, shapeBuilder));
                        } else {
                            filters
                                    .add(QueryBuilders
                                                 .geoDistanceQuery(propertyName)
                                                 .point(lat, lon)
                                                 .distance(distance, DistanceUnit.KILOMETERS));
                        }
                    } else if (value instanceof GeoRect) {
                        GeoRect geoRect = (GeoRect) value;
                        double nwLat = geoRect.getNorthWest().getLatitude();
                        double nwLon = geoRect.getNorthWest().getLongitude();
                        double seLat = geoRect.getSouthEast().getLatitude();
                        double seLon = geoRect.getSouthEast().getLongitude();

                        String inflatedPropertyName = getSearchIndex().inflatePropertyName(propertyName);
                        PropertyDefinition propertyDefinition = getGraph().getPropertyDefinition(inflatedPropertyName);
                        if (propertyDefinition != null && propertyDefinition.getDataType() == GeoCircle.class) {
                            ShapeBuilder shapeBuilder = ShapeBuilder.newPolygon()
                                    .point(nwLon, nwLat)
                                    .point(seLon, nwLat)
                                    .point(seLon, seLat)
                                    .point(nwLon, seLat)
                                    .close();
                            filters
                                    .add(new GeoShapeQueryBuilder(propertyName, shapeBuilder));
                        } else {
                            filters
                                    .add(QueryBuilders
                                                 .geoBoundingBoxQuery(propertyName)
                                                 .topLeft(nwLat, nwLon)
                                                 .bottomRight(seLat, seLon));
                        }
                    } else {
                        throw new VertexiumException("Unexpected has value type " + value.getClass().getName());
                    }
                    break;
                default:
                    throw new VertexiumException("Unexpected GeoCompare predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters, has);
    }

    private QueryBuilder getSingleFilterOrOrTheFilters(List<QueryBuilder> filters, HasContainer has) {
        if (filters.size() > 1) {
            return QueryBuilders.orQuery(filters.toArray(new QueryBuilder[filters.size()]));
        } else if (filters.size() == 1) {
            return filters.get(0);
        } else {
            throw new VertexiumException("Unexpected filter count, expected at least 1 filter for: " + has);
        }
    }

    private QueryBuilder getSingleFilterOrAndTheFilters(List<QueryBuilder> filters, HasContainer has) {
        if (filters.size() > 1) {
            return QueryBuilders.andQuery(filters.toArray(new QueryBuilder[filters.size()]));
        } else if (filters.size() == 1) {
            return filters.get(0);
        } else {
            throw new VertexiumException("Unexpected filter count, expected at least 1 filter for: " + has);
        }
    }

    protected QueryBuilder getFilterForTextPredicate(TextPredicate compare, HasValueContainer has) {
        Object value = has.value;
        String[] keys = getPropertyNames(has.key);
        if (keys.length == 0) {
            throw new VertexiumNoMatchingPropertiesException(has.key);
        }
        List<QueryBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            if (value instanceof String) {
                value = ((String) value).toLowerCase(); // using the standard analyzer all strings are lower-cased.
            }
            switch (compare) {
                case CONTAINS:
                    if (value instanceof String) {
                        String[] terms = splitStringIntoTerms((String) value);
                        filters.add(QueryBuilders.termsQuery(key, terms));
                    } else {
                        filters.add(QueryBuilders.termQuery(key, value));
                    }
                    break;
                default:
                    throw new VertexiumException("Unexpected text predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters, has);
    }

    protected QueryBuilder getFilterForContainsPredicate(Contains contains, HasValueContainer has) {
        String[] keys = getPropertyNames(has.key);
        if (keys.length == 0) {
            if (contains.equals(Contains.NOT_IN)) {
                return QueryBuilders.matchAllQuery();
            }
            throw new VertexiumNoMatchingPropertiesException(has.key);
        }
        List<QueryBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            if (has.value instanceof Iterable) {
                has.value = IterableUtils.toArray((Iterable<?>) has.value, Object.class);
            }
            if (has.value instanceof String
                    || has.value instanceof String[]
                    || (has.value instanceof Object[] && ((Object[]) has.value).length > 0 && ((Object[]) has.value)[0] instanceof String)
                    ) {
                key = key + Elasticsearch2SearchIndex.EXACT_MATCH_PROPERTY_NAME_SUFFIX;
            }
            switch (contains) {
                case IN:
                    filters.add(QueryBuilders.termsQuery(key, (Object[]) has.value));
                    break;
                case NOT_IN:
                    filters.add(QueryBuilders.notQuery(QueryBuilders.termsQuery(key, (Object[]) has.value)));
                    break;
                default:
                    throw new VertexiumException("Unexpected Contains predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters, has);
    }

    protected QueryBuilder getFilterForComparePredicate(Compare compare, HasValueContainer has) {
        Object value = has.value;
        String[] keys = getPropertyNames(has.key);
        if (keys.length == 0) {
            if (compare.equals(Compare.NOT_EQUAL)) {
                return QueryBuilders.matchAllQuery();
            }
            throw new VertexiumNoMatchingPropertiesException(has.key);
        }
        List<QueryBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            if (value instanceof String || value instanceof String[]) {
                key = key + Elasticsearch2SearchIndex.EXACT_MATCH_PROPERTY_NAME_SUFFIX;
            }
            switch (compare) {
                case EQUAL:
                    if (value instanceof DateOnly) {
                        DateOnly dateOnlyValue = ((DateOnly) value);
                        String lower = dateOnlyValue.toString() + "T00:00:00.000Z";
                        String upper = dateOnlyValue.toString() + "T23:59:59.999Z";
                        filters.add(QueryBuilders.rangeQuery(key).gte(lower).lte(upper));
                    } else {
                        filters.add(QueryBuilders.termQuery(key, value));
                    }
                    break;
                case GREATER_THAN_EQUAL:
                    filters.add(QueryBuilders.rangeQuery(key).gte(value));
                    break;
                case GREATER_THAN:
                    filters.add(QueryBuilders.rangeQuery(key).gt(value));
                    break;
                case LESS_THAN_EQUAL:
                    filters.add(QueryBuilders.rangeQuery(key).lte(value));
                    break;
                case LESS_THAN:
                    filters.add(QueryBuilders.rangeQuery(key).lt(value));
                    break;
                case NOT_EQUAL:
                    addNotFilter(filters, key, value);
                    break;
                default:
                    throw new VertexiumException("Unexpected Compare predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters, has);
    }

    protected String[] getPropertyNames(String propertyName) {
        return getSearchIndex().getAllMatchingPropertyNames(getGraph(), propertyName, getParameters().getAuthorizations());
    }

    protected Elasticsearch2SearchIndex getSearchIndex() {
        return (Elasticsearch2SearchIndex) ((GraphWithSearchIndex) getGraph()).getSearchIndex();
    }

    protected void addElementTypeFilter(List<QueryBuilder> filters, EnumSet<ElasticsearchDocumentType> elementType) {
        if (elementType != null) {
            filters.add(createElementTypeFilter(elementType));
        }
    }

    protected TermsQueryBuilder createElementTypeFilter(EnumSet<ElasticsearchDocumentType> elementType) {
        List<String> values = new ArrayList<>();
        for (ElasticsearchDocumentType et : elementType) {
            values.add(et.getKey());
        }
        return QueryBuilders.termsQuery(
                Elasticsearch2SearchIndex.ELEMENT_TYPE_FIELD_NAME,
                values.toArray(new String[values.size()])
        );
    }

    protected void addNotFilter(List<QueryBuilder> filters, String key, Object value) {
        filters.add(QueryBuilders.notQuery(QueryBuilders.termQuery(key, value)));
    }

    protected AndQueryBuilder getFilterBuilder(List<QueryBuilder> filters) {
        return QueryBuilders.andQuery(filters.toArray(new QueryBuilder[filters.size()]));
    }

    private String[] splitStringIntoTerms(String value) {
        try {
            List<String> results = new ArrayList<>();
            try (TokenStream tokens = analyzer.tokenStream("", value)) {
                CharTermAttribute term = tokens.getAttribute(CharTermAttribute.class);
                tokens.reset();
                while (tokens.incrementToken()) {
                    String t = term.toString().trim();
                    if (t.length() > 0) {
                        results.add(t);
                    }
                }
            }
            return results.toArray(new String[results.size()]);
        } catch (IOException e) {
            throw new VertexiumException("Could not tokenize string: " + value, e);
        }
    }

    protected QueryBuilder createQuery(QueryParameters queryParameters) {
        if (queryParameters instanceof QueryStringQueryParameters) {
            return createQueryStringQuery((QueryStringQueryParameters) queryParameters);
        } else if (queryParameters instanceof SimilarToTextQueryParameters) {
            return createSimilarToTextQuery((SimilarToTextQueryParameters) queryParameters);
        } else {
            throw new VertexiumException("Query parameters not supported of type: " + queryParameters.getClass().getName());
        }
    }

    protected QueryBuilder createSimilarToTextQuery(SimilarToTextQueryParameters similarTo) {
        List<String> allFields = new ArrayList<>();
        String[] fields = similarTo.getFields();
        for (String field : fields) {
            Collections.addAll(allFields, getPropertyNames(field));
        }
        MoreLikeThisQueryBuilder q = QueryBuilders.moreLikeThisQuery(allFields.toArray(new String[allFields.size()]))
                .likeText(similarTo.getText());
        if (similarTo.getMinTermFrequency() != null) {
            q.minTermFreq(similarTo.getMinTermFrequency());
        }
        if (similarTo.getMaxQueryTerms() != null) {
            q.maxQueryTerms(similarTo.getMaxQueryTerms());
        }
        if (similarTo.getMinDocFrequency() != null) {
            q.minDocFreq(similarTo.getMinDocFrequency());
        }
        if (similarTo.getMaxDocFrequency() != null) {
            q.maxDocFreq(similarTo.getMaxDocFrequency());
        }
        if (similarTo.getBoost() != null) {
            q.boost(similarTo.getBoost());
        }
        return q;
    }

    public Client getClient() {
        return client;
    }

    protected List<AbstractAggregationBuilder> getElasticsearchAggregations(Iterable<Aggregation> aggregations) {
        List<AbstractAggregationBuilder> aggs = new ArrayList<>();
        for (Aggregation agg : aggregations) {
            if (agg instanceof HistogramAggregation) {
                aggs.addAll(getElasticsearchHistogramAggregations((HistogramAggregation) agg));
            } else if (agg instanceof RangeAggregation) {
                aggs.addAll(getElasticsearchRangeAggregations((RangeAggregation) agg));
            } else if (agg instanceof PercentilesAggregation) {
                aggs.addAll(getElasticsearchPercentilesAggregations((PercentilesAggregation) agg));
            } else if (agg instanceof TermsAggregation) {
                aggs.addAll(getElasticsearchTermsAggregations((TermsAggregation) agg));
            } else if (agg instanceof GeohashAggregation) {
                aggs.addAll(getElasticsearchGeohashAggregations((GeohashAggregation) agg));
            } else if (agg instanceof StatisticsAggregation) {
                aggs.addAll(getElasticsearchStatisticsAggregations((StatisticsAggregation) agg));
            } else if (agg instanceof CalendarFieldAggregation) {
                aggs.addAll(getElasticsearchCalendarFieldAggregation((CalendarFieldAggregation) agg));
            } else {
                throw new VertexiumException("Could not add aggregation of type: " + agg.getClass().getName());
            }
        }
        return aggs;
    }

    protected List<AggregationBuilder> getElasticsearchGeohashAggregations(GeohashAggregation agg) {
        List<AggregationBuilder> aggs = new ArrayList<>();
        for (String propertyName : getPropertyNames(agg.getFieldName())) {
            String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
            String aggName = createAggregationName(agg.getAggregationName(), visibilityHash);
            GeoHashGridBuilder geoHashAgg = AggregationBuilders.geohashGrid(aggName);
            geoHashAgg.field(propertyName + Elasticsearch2SearchIndex.GEO_PROPERTY_NAME_SUFFIX);
            geoHashAgg.precision(agg.getPrecision());
            aggs.add(geoHashAgg);
        }
        return aggs;
    }

    protected List<AbstractAggregationBuilder> getElasticsearchStatisticsAggregations(StatisticsAggregation agg) {
        List<AbstractAggregationBuilder> aggs = new ArrayList<>();
        for (String propertyName : getPropertyNames(agg.getFieldName())) {
            String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
            String aggName = createAggregationName(agg.getAggregationName(), visibilityHash);
            ExtendedStatsBuilder statsAgg = AggregationBuilders.extendedStats(aggName);
            statsAgg.field(propertyName);
            aggs.add(statsAgg);
        }
        return aggs;
    }

    protected List<AbstractAggregationBuilder> getElasticsearchPercentilesAggregations(PercentilesAggregation agg) {
        String propertyName = getSearchIndex().deflatePropertyName(getGraph(), agg.getFieldName(), agg.getVisibility());
        String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
        String aggName = createAggregationName(agg.getAggregationName(), visibilityHash);
        PercentilesBuilder percentilesAgg = AggregationBuilders.percentiles(aggName);
        percentilesAgg.field(propertyName);
        if (agg.getPercents() != null && agg.getPercents().length > 0) {
            percentilesAgg.percentiles(agg.getPercents());
        }
        return Collections.singletonList(percentilesAgg);
    }

    private String createAggregationName(String aggName, String visibilityHash) {
        if (visibilityHash != null && visibilityHash.length() > 0) {
            return aggName + "_" + visibilityHash;
        }
        return aggName;
    }

    protected List<AggregationBuilder> getElasticsearchTermsAggregations(TermsAggregation agg) {
        List<AggregationBuilder> termsAggs = new ArrayList<>();
        String fieldName = agg.getPropertyName();
        if (Edge.LABEL_PROPERTY_NAME.equals(fieldName)) {
            TermsBuilder termsAgg = AggregationBuilders.terms(createAggregationName(agg.getAggregationName(), "0"));
            termsAgg.field(fieldName);
            termsAggs.add(termsAgg);
        } else {
            PropertyDefinition propertyDefinition = getPropertyDefinition(fieldName);
            for (String propertyName : getPropertyNames(fieldName)) {
                if (isExactMatchPropertyDefinition(propertyDefinition)) {
                    propertyName = propertyName + Elasticsearch2SearchIndex.EXACT_MATCH_PROPERTY_NAME_SUFFIX;
                }

                String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
                TermsBuilder termsAgg = AggregationBuilders.terms(createAggregationName(agg.getAggregationName(), visibilityHash));
                termsAgg.field(propertyName);
                if (agg.getSize() != null) {
                    termsAgg.size(agg.getSize());
                }

                for (AbstractAggregationBuilder subAgg : getElasticsearchAggregations(agg.getNestedAggregations())) {
                    termsAgg.subAggregation(subAgg);
                }

                termsAggs.add(termsAgg);
            }
        }
        return termsAggs;
    }

    private boolean isExactMatchPropertyDefinition(PropertyDefinition propertyDefinition) {
        return propertyDefinition != null
                && propertyDefinition.getDataType().equals(String.class)
                && propertyDefinition.getTextIndexHints().contains(TextIndexHint.EXACT_MATCH);
    }

    private Collection<? extends AbstractAggregationBuilder> getElasticsearchCalendarFieldAggregation(CalendarFieldAggregation agg) {
        List<AggregationBuilder> aggs = new ArrayList<>();
        PropertyDefinition propertyDefinition = getPropertyDefinition(agg.getPropertyName());
        if (propertyDefinition == null) {
            throw new VertexiumException("Could not find mapping for property: " + agg.getPropertyName());
        }
        Class propertyDataType = propertyDefinition.getDataType();
        for (String propertyName : getPropertyNames(agg.getPropertyName())) {
            String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
            String aggName = createAggregationName(agg.getAggregationName(), visibilityHash);
            if (propertyDataType == Date.class) {
                HistogramBuilder histAgg = AggregationBuilders.histogram(aggName);
                histAgg.interval(1);
                if (agg.getMinDocumentCount() != null) {
                    histAgg.minDocCount(agg.getMinDocumentCount());
                } else {
                    histAgg.minDocCount(1L);
                }
                Script script = new Script(getCalendarFieldAggregationScript(agg, propertyName));
                histAgg.script(script);

                for (AbstractAggregationBuilder subAgg : getElasticsearchAggregations(agg.getNestedAggregations())) {
                    histAgg.subAggregation(subAgg);
                }

                aggs.add(histAgg);
            } else {
                throw new VertexiumException("Only dates are supported for hour of day aggregations");
            }
        }
        return aggs;
    }

    private String getCalendarFieldAggregationScript(CalendarFieldAggregation agg, String propertyName) {
        String prefix = "d = doc['" + propertyName + "']; ";
        switch (agg.getCalendarField()) {
            case Calendar.DAY_OF_MONTH:
                return prefix + "d ? d.date.toDateTime(DateTimeZone.forID(\"" + agg.getTimeZone().getID() + "\")).get(DateTimeFieldType.dayOfMonth()) : -1";
            case Calendar.DAY_OF_WEEK:
                return prefix + "d = (d ? (d.date.toDateTime(DateTimeZone.forID(\"" + agg.getTimeZone().getID() + "\")).get(DateTimeFieldType.dayOfWeek()) + 1) : -1); return d > 7 ? d - 7 : d;";
            case Calendar.HOUR_OF_DAY:
                return prefix + "d ? d.date.toDateTime(DateTimeZone.forID(\"" + agg.getTimeZone().getID() + "\")).get(DateTimeFieldType.hourOfDay()) : -1";
            case Calendar.MONTH:
                return prefix + "d ? (d.date.toDateTime(DateTimeZone.forID(\"" + agg.getTimeZone().getID() + "\")).get(DateTimeFieldType.monthOfYear()) - 1) : -1";
            case Calendar.YEAR:
                return prefix + "d ? d.date.toDateTime(DateTimeZone.forID(\"" + agg.getTimeZone().getID() + "\")).get(DateTimeFieldType.year()) : -1";
            default:
                LOGGER.warn("Slow operation toGregorianCalendar() for calendar field: %d", agg.getCalendarField());
                return prefix + "d ? d.date.toDateTime(DateTimeZone.forID(\"" + agg.getTimeZone().getID() + "\")).toGregorianCalendar().get(" + agg.getCalendarField() + ") : -1";
        }
    }

    protected List<AggregationBuilder> getElasticsearchHistogramAggregations(HistogramAggregation agg) {
        List<AggregationBuilder> aggs = new ArrayList<>();
        PropertyDefinition propertyDefinition = getPropertyDefinition(agg.getFieldName());
        if (propertyDefinition == null) {
            throw new VertexiumException("Could not find mapping for property: " + agg.getFieldName());
        }
        Class propertyDataType = propertyDefinition.getDataType();
        for (String propertyName : getPropertyNames(agg.getFieldName())) {
            String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
            String aggName = createAggregationName(agg.getAggregationName(), visibilityHash);
            if (propertyDataType == Date.class) {
                DateHistogramBuilder dateAgg = AggregationBuilders.dateHistogram(aggName);
                dateAgg.field(propertyName);
                String interval = agg.getInterval();
                if (Pattern.matches("^[0-9\\.]+$", interval)) {
                    interval += "ms";
                }
                dateAgg.interval(new DateHistogramInterval(interval));
                dateAgg.minDocCount(1L);
                if (agg.getMinDocumentCount() != null) {
                    dateAgg.minDocCount(agg.getMinDocumentCount());
                }
                if (agg.getExtendedBounds() != null) {
                    HistogramAggregation.ExtendedBounds<?> bounds = agg.getExtendedBounds();
                    if (bounds.getMinMaxType().isAssignableFrom(Long.class)) {
                        dateAgg.extendedBounds((Long) bounds.getMin(), (Long) bounds.getMax());
                    } else if (bounds.getMinMaxType().isAssignableFrom(Date.class)) {
                        dateAgg.extendedBounds(new DateTime(bounds.getMin()), new DateTime(bounds.getMax()));
                    } else if (bounds.getMinMaxType().isAssignableFrom(String.class)) {
                        dateAgg.extendedBounds((String) bounds.getMin(), (String) bounds.getMax());
                    } else {
                        throw new VertexiumException("Unhandled extended bounds type. Expected Long, String, or Date. Found: " + bounds.getMinMaxType().getName());
                    }
                }

                for (AbstractAggregationBuilder subAgg : getElasticsearchAggregations(agg.getNestedAggregations())) {
                    dateAgg.subAggregation(subAgg);
                }

                aggs.add(dateAgg);
            } else {
                HistogramBuilder histogramAgg = AggregationBuilders.histogram(aggName);
                histogramAgg.field(propertyName);
                histogramAgg.interval(Long.parseLong(agg.getInterval()));
                histogramAgg.minDocCount(1L);
                if (agg.getMinDocumentCount() != null) {
                    histogramAgg.minDocCount(agg.getMinDocumentCount());
                }
                if (agg.getExtendedBounds() != null) {
                    HistogramAggregation.ExtendedBounds<?> bounds = agg.getExtendedBounds();
                    if (bounds.getMinMaxType().isAssignableFrom(Long.class)) {
                        histogramAgg.extendedBounds((Long) bounds.getMin(), (Long) bounds.getMax());
                    } else {
                        throw new VertexiumException("Unhandled extended bounds type. Expected Long. Found: " + bounds.getMinMaxType().getName());
                    }
                }

                for (AbstractAggregationBuilder subAgg : getElasticsearchAggregations(agg.getNestedAggregations())) {
                    histogramAgg.subAggregation(subAgg);
                }

                aggs.add(histogramAgg);
            }
        }
        return aggs;
    }

    protected List<AggregationBuilder> getElasticsearchRangeAggregations(RangeAggregation agg) {
        List<AggregationBuilder> aggs = new ArrayList<>();
        PropertyDefinition propertyDefinition = getPropertyDefinition(agg.getFieldName());
        if (propertyDefinition == null) {
            throw new VertexiumException("Could not find mapping for property: " + agg.getFieldName());
        }
        Class propertyDataType = propertyDefinition.getDataType();
        for (String propertyName : getPropertyNames(agg.getFieldName())) {
            String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
            String aggName = createAggregationName(agg.getAggregationName(), visibilityHash);
            if (propertyDataType == Date.class) {
                DateRangeBuilder dateRangeBuilder = AggregationBuilders.dateRange(aggName);
                dateRangeBuilder.field(propertyName);

                if (!Strings.isNullOrEmpty(agg.getFormat())) {
                    dateRangeBuilder.format(agg.getFormat());
                }

                for (RangeAggregation.Range range : agg.getRanges()) {
                    dateRangeBuilder.addRange(range.getKey(), range.getFrom(), range.getTo());
                }

                for (AbstractAggregationBuilder subAgg : getElasticsearchAggregations(agg.getNestedAggregations())) {
                    dateRangeBuilder.subAggregation(subAgg);
                }

                aggs.add(dateRangeBuilder);
            } else {
                RangeBuilder rangeBuilder = AggregationBuilders.range(aggName);
                rangeBuilder.field(propertyName);

                if (!Strings.isNullOrEmpty(agg.getFormat())) {
                    throw new VertexiumException("Invalid use of format for property: " + agg.getFieldName() +
                                                         ". Format is only valid for date properties");
                }

                for (RangeAggregation.Range range : agg.getRanges()) {
                    Object from = range.getFrom();
                    Object to = range.getTo();
                    if ((from != null && !(from instanceof Number)) || (to != null && !(to instanceof Number))) {
                        throw new VertexiumException("Invalid range for property: " + agg.getFieldName() +
                                                             ". Both to and from must be Numeric.");
                    }
                    rangeBuilder.addRange(
                            range.getKey(),
                            from == null ? Double.MIN_VALUE : ((Number) from).doubleValue(),
                            to == null ? Double.MAX_VALUE : ((Number) to).doubleValue()
                    );
                }

                for (AbstractAggregationBuilder subAgg : getElasticsearchAggregations(agg.getNestedAggregations())) {
                    rangeBuilder.subAggregation(subAgg);
                }

                aggs.add(rangeBuilder);
            }
        }
        return aggs;
    }

    protected PropertyDefinition getPropertyDefinition(String propertyName) {
        return getGraph().getPropertyDefinition(propertyName);
    }

    protected IndexSelectionStrategy getIndexSelectionStrategy() {
        return indexSelectionStrategy;
    }

    public String getAggregationName(String name) {
        return getSearchIndex().getAggregationName(name);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "{" +
                "parameters=" + getParameters() +
                ", evaluateHasContainers=" + evaluateHasContainers +
                ", evaluateQueryString=" + evaluateQueryString +
                ", evaluateSortContainers=" + evaluateSortContainers +
                ", pageSize=" + pageSize +
                '}';
    }

    private static class Ids {
        private final List<String> vertexIds;
        private final List<String> edgeIds;
        private final List<String> ids;
        private final List<ExtendedDataRowId> extendedDataIds;

        public Ids(SearchHits hits) {
            vertexIds = new ArrayList<>();
            edgeIds = new ArrayList<>();
            extendedDataIds = new ArrayList<>();
            ids = new ArrayList<>();
            for (SearchHit hit : hits) {
                ElasticsearchDocumentType dt = ElasticsearchDocumentType.fromSearchHit(hit);
                if (dt == null) {
                    continue;
                }
                String id = hit.getId();
                switch (dt) {
                    case VERTEX:
                        ids.add(id);
                        vertexIds.add(id);
                        break;
                    case EDGE:
                        ids.add(id);
                        edgeIds.add(id);
                        break;
                    case VERTEX_EXTENDED_DATA:
                    case EDGE_EXTENDED_DATA:
                        ids.add(id);
                        extendedDataIds.add(ElasticsearchExtendedDataIdUtils.fromSearchHit(hit));
                        break;
                    default:
                        LOGGER.warn("Unhandled document type: %s", dt);
                        break;
                }
            }
        }

        public List<String> getVertexIds() {
            return vertexIds;
        }

        public List<String> getEdgeIds() {
            return edgeIds;
        }

        public List<String> getIds() {
            return ids;
        }

        public List<ExtendedDataRowId> getExtendedDataIds() {
            return extendedDataIds;
        }
    }
}
