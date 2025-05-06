package org.icij.datashare.db;

import org.icij.datashare.batch.*;
import org.icij.datashare.batch.BatchSearchRecord.State;
import org.icij.datashare.db.tables.records.BatchSearchProjectRecord;
import org.icij.datashare.db.tables.records.BatchSearchQueryRecord;
import org.icij.datashare.db.tables.records.BatchSearchResultRecord;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.IntStream;

import static java.lang.String.join;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.*;
import static org.icij.datashare.batch.BatchSearchRepository.WebQuery.DEFAULT_SORT_FIELD;
import static org.icij.datashare.db.Tables.BATCH_SEARCH_PROJECT;
import static org.icij.datashare.db.tables.BatchSearch.BATCH_SEARCH;
import static org.icij.datashare.db.tables.BatchSearchQuery.BATCH_SEARCH_QUERY;
import static org.icij.datashare.db.tables.BatchSearchResult.BATCH_SEARCH_RESULT;
import static org.icij.datashare.text.ProjectProxy.asNameList;
import static org.icij.datashare.text.ProjectProxy.proxy;
import static org.jooq.impl.DSL.*;

public class JooqBatchSearchRepository implements BatchSearchRepository {
    private static final String LIST_SEPARATOR = ",";
    final DataSource dataSource;
    final SQLDialect dialect;

    JooqBatchSearchRepository(final DataSource dataSource, final SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public boolean save(final BatchSearch batchSearch) {
        DSLContext context = DSL.using(dataSource, dialect);
        return context.transactionResult(configuration -> {
            DSLContext inner = DSL.using(configuration);
            inner.insertInto(BATCH_SEARCH, BATCH_SEARCH.UUID, BATCH_SEARCH.NAME, BATCH_SEARCH.DESCRIPTION, BATCH_SEARCH.URI, BATCH_SEARCH.USER_ID,
                            BATCH_SEARCH.BATCH_DATE, BATCH_SEARCH.STATE, BATCH_SEARCH.PUBLISHED, BATCH_SEARCH.FILE_TYPES, BATCH_SEARCH.QUERY_TEMPLATE,
                            BATCH_SEARCH.PATHS, BATCH_SEARCH.FUZZINESS, BATCH_SEARCH.PHRASE_MATCHES, BATCH_SEARCH.NB_QUERIES, BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS).
                    values(batchSearch.uuid, batchSearch.name, batchSearch.description, batchSearch.uri, batchSearch.user.id,
                            new Timestamp(batchSearch.date.getTime()).toLocalDateTime(), batchSearch.state.name(), batchSearch.published ? 1 : 0,
                            join(LIST_SEPARATOR, batchSearch.fileTypes), batchSearch.queryTemplate.toString(),
                            join(LIST_SEPARATOR, batchSearch.paths), batchSearch.fuzziness, batchSearch.phraseMatches ? 1 : 0, batchSearch.nbQueries,
                            batchSearch.nbQueriesWithoutResults).execute();

            InsertValuesStep4<BatchSearchQueryRecord, String, String, Integer, Integer> insertQuery = inner.insertInto(BATCH_SEARCH_QUERY, BATCH_SEARCH_QUERY.SEARCH_UUID, BATCH_SEARCH_QUERY.QUERY, BATCH_SEARCH_QUERY.QUERY_NUMBER, BATCH_SEARCH_QUERY.QUERY_RESULTS);
            List<String> queries = new ArrayList<>(batchSearch.queries.keySet());
            IntStream.range(0, queries.size()).forEach(i -> insertQuery.values(batchSearch.uuid, queries.get(i), i, 0));
            InsertValuesStep2<BatchSearchProjectRecord, String, String>
                    insertProject = inner.insertInto(BATCH_SEARCH_PROJECT, BATCH_SEARCH_PROJECT.SEARCH_UUID, BATCH_SEARCH_PROJECT.PRJ_ID);
            batchSearch.projects.forEach(project -> insertProject.values(batchSearch.uuid, project.getId()));
            return insertQuery.execute() + insertProject.execute() > 0;

        });

    }

    @Override
    public boolean saveResults(String batchSearchId, String query, List<Document> documents) {
        return saveResults(batchSearchId, query, documents, true);
    }

    @Override
    public boolean saveResults(String batchSearchId, String query, List<Document> documents, boolean isFirstScroll) {
        DSLContext context = DSL.using(dataSource, dialect);
        return context.transactionResult(configuration -> {
            DSLContext inner = DSL.using(configuration);
            UpdateSetMoreStep<BatchSearchQueryRecord> updateBatchSearchQuery = inner.update(BATCH_SEARCH_QUERY).set(BATCH_SEARCH_QUERY.QUERY_RESULTS,
                    BATCH_SEARCH_QUERY.QUERY_RESULTS.plus(documents.size()));
            updateBatchSearchQuery.
                    where(BATCH_SEARCH_QUERY.SEARCH_UUID.eq(batchSearchId).
                            and(BATCH_SEARCH_QUERY.QUERY.eq(query))).execute();


            UpdateSetMoreStep<org.icij.datashare.db.tables.records.BatchSearchRecord> updateBatchSearch = inner.update(BATCH_SEARCH)
                    .set(BATCH_SEARCH.BATCH_RESULTS, BATCH_SEARCH.BATCH_RESULTS.plus(documents.size()))
                    .set(BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS, BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS.minus(isFirstScroll ? 1 : 0));
            updateBatchSearch.
                    where(BATCH_SEARCH.UUID.eq(batchSearchId)).execute();


            InsertValuesStep10<BatchSearchResultRecord, String, String, Integer, String, String, String, LocalDateTime, String, Long, String>
                    insertQuery =
                    inner.insertInto(BATCH_SEARCH_RESULT, BATCH_SEARCH_RESULT.SEARCH_UUID, BATCH_SEARCH_RESULT.QUERY, BATCH_SEARCH_RESULT.DOC_NB,
                            BATCH_SEARCH_RESULT.DOC_ID, BATCH_SEARCH_RESULT.ROOT_ID, BATCH_SEARCH_RESULT.DOC_PATH, BATCH_SEARCH_RESULT.CREATION_DATE,
                            BATCH_SEARCH_RESULT.CONTENT_TYPE, BATCH_SEARCH_RESULT.CONTENT_LENGTH, BATCH_SEARCH_RESULT.PRJ_ID);
            IntStream.range(0, documents.size()).forEach(i -> insertQuery.values(batchSearchId, query, i,
                    documents.get(i).getId(), documents.get(i).getRootDocument(), documents.get(i).getPath().toString(),
                    documents.get(i).getCreationDate() == null ? null :
                            new Timestamp(documents.get(i).getCreationDate().getTime()).toLocalDateTime(),
                    documents.get(i).getContentType(), documents.get(i).getContentLength(), documents.get(i).getProject().getId()));
            return insertQuery.execute() > 0;
        });

    }

    @Override
    public boolean setState(String batchSearchId, State state) {
        UpdateSetMoreStep<org.icij.datashare.db.tables.records.BatchSearchRecord> updateBatchSearch = DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.STATE, state.name());
        return updateBatchSearch.
                where(BATCH_SEARCH.UUID.eq(batchSearchId)).execute() > 0;

    }

    @Override
    public boolean setState(String batchSearchId, SearchException error) {
        UpdateSetMoreStep<org.icij.datashare.db.tables.records.BatchSearchRecord> updateBatchSearch = using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.STATE, State.FAILURE.name());
        return updateBatchSearch.
                set(BATCH_SEARCH.ERROR_MESSAGE, error.toString()).
                set(BATCH_SEARCH.ERROR_QUERY, error.query).
                where(BATCH_SEARCH.UUID.eq(batchSearchId)).execute() > 0;

    }

    @Override
    public boolean deleteAll(User user) {
        DSLContext context = DSL.using(dataSource, dialect);
        return context.transactionResult(configuration -> {
            DSLContext inner = DSL.using(configuration);
            inner.deleteFrom(BATCH_SEARCH_QUERY).where(BATCH_SEARCH_QUERY.SEARCH_UUID.
                            in(inner.select(BATCH_SEARCH.UUID).from(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)))).
                    execute();
            inner.deleteFrom(BATCH_SEARCH_RESULT).where(BATCH_SEARCH_RESULT.SEARCH_UUID.
                            in(inner.select(BATCH_SEARCH.UUID).from(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)))).
                    execute();
            inner.deleteFrom(BATCH_SEARCH_PROJECT).where(BATCH_SEARCH_PROJECT.SEARCH_UUID.
                            in(inner.select(BATCH_SEARCH.UUID).from(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)))).
                    execute();
            return inner.deleteFrom(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)).execute() > 0;

        });

    }

    @Override
    public boolean delete(User user, String batchId) {
        DSLContext context = DSL.using(dataSource, dialect);
        return context.transactionResult(configuration -> {
            DSLContext inner = DSL.using(configuration);
            SelectSelectStep<Record1<String>> batchSearchUUID = select(BATCH_SEARCH.UUID);
            SelectConditionStep<Record1<String>> batch_uuid = batchSearchUUID.from(BATCH_SEARCH).
                    where(BATCH_SEARCH.USER_ID.eq(user.id)).and(BATCH_SEARCH.UUID.eq(batchId));
            inner.deleteFrom(BATCH_SEARCH_QUERY).where(BATCH_SEARCH_QUERY.SEARCH_UUID.in(batch_uuid)).execute();
            inner.deleteFrom(BATCH_SEARCH_RESULT).where(BATCH_SEARCH_RESULT.SEARCH_UUID.in(batch_uuid)).execute();
            inner.deleteFrom(BATCH_SEARCH_PROJECT).where(BATCH_SEARCH_PROJECT.SEARCH_UUID.in(batch_uuid)).execute();
            return inner.deleteFrom(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)).
                    and(BATCH_SEARCH.UUID.eq(batchId)).and(BATCH_SEARCH.STATE.ne(State.RUNNING.name())).execute() > 0;


        });

    }

    @Override
    public BatchSearch get(String id) {
        Optional<BatchSearch> batchSearches = mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).where(BATCH_SEARCH.UUID.eq(id)).
                        fetch().stream().map(this::createBatchSearchFrom).collect(toList())).stream().findFirst();
        return batchSearches.orElseThrow(() -> new BatchNotFoundException(id));
    }

    @Override
    public int getTotal(User user, List<String> projectsIds, WebQuery webQuery) {
        DSLContext context = DSL.using(dataSource, dialect);
        SelectOnConditionStep<Record1<Integer>> selectBatchSearchUUID = context.select(countDistinct(BATCH_SEARCH_PROJECT.SEARCH_UUID)).
                from(BATCH_SEARCH_PROJECT).join(BATCH_SEARCH).on(BATCH_SEARCH_PROJECT.SEARCH_UUID.equal(BATCH_SEARCH.UUID));
        SelectConditionStep<Record1<Integer>> query = selectBatchSearchUUID.
                where(BATCH_SEARCH.USER_ID.eq(user.id).or(BATCH_SEARCH.PUBLISHED.greaterThan(0)));
        addFilterToSelectCondition(webQuery, query);
        return query.groupBy(BATCH_SEARCH.UUID).having(count().eq(
                context.selectCount().from(BATCH_SEARCH_PROJECT)
                        .where(BATCH_SEARCH_PROJECT.SEARCH_UUID.eq(BATCH_SEARCH.UUID).and(BATCH_SEARCH_PROJECT.PRJ_ID.in(projectsIds)))
        )).fetch(0,int.class).stream().reduce(0, Integer::sum);

    }

    @Override
    public List<BatchSearchRecord> getRecords(User user, List<String> projectsIds) {
        return getRecords(user, projectsIds, WebQueryBuilder.createWebQuery().queryAll().build());
    }

    @Override
    public List<BatchSearchRecord> getRecords(User user, List<String> projectsIds, WebQuery webQuery) {
        DSLContext context = DSL.using(dataSource, dialect);
        cacheNbQueries(webQuery, context);

        SelectConditionStep<Record14<String, String, String, String,String, LocalDateTime, String, Integer, Integer, String, String, String, Integer, Integer>> query = createBatchSearchRecordWithQueriesSelectStatement(context)
                .where(BATCH_SEARCH.USER_ID.eq(user.id).or(BATCH_SEARCH.PUBLISHED.greaterThan(0)));
        List<String> filteredProjects = webQuery.hasFilteredProjects() ? asNameList(webQuery.project) : projectsIds;
        addFilterToSelectCondition(webQuery, query);
        if (webQuery.isSorted()) {
            query.orderBy(field(webQuery.sort + " " + webQuery.order));
        } else {
            query.orderBy(field("batch_date desc"));
        }
        if (webQuery.size > 0) query.limit(webQuery.size);
        if (webQuery.from > 0) query.offset(webQuery.from);
        return query.groupBy(BATCH_SEARCH.UUID).having(count().eq(
                        context.selectCount().from(BATCH_SEARCH_PROJECT)
                                .where(BATCH_SEARCH_PROJECT.SEARCH_UUID.eq(BATCH_SEARCH.UUID).and(BATCH_SEARCH_PROJECT.PRJ_ID.in(filteredProjects)))
                )).orderBy(BATCH_SEARCH.BATCH_DATE.desc()).
                fetch().stream().map(this::createBatchSearchRecordFrom).collect(toList());

    }

    @Override
    public BatchSearch get(User user, String batchId) {
        return mergeBatchSearches(createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect))
                .where(BATCH_SEARCH.UUID.eq(batchId)).fetch()
                .stream()
                .map(this::createBatchSearchFrom).collect(toList()))
                .stream().findFirst().orElse(null);
    }

    @Override
    public BatchSearch get(User user, String batchId, boolean withQueries) {
        if (withQueries){
            return get(user, batchId);
        }
        DSLContext context = DSL.using(dataSource, dialect);
        return createBatchSearchWithoutQueriesSelectStatement(context)
                .where(BATCH_SEARCH.UUID.eq(batchId))
                .groupBy(BATCH_SEARCH.UUID).having(count().eq(
                        context.selectCount().from(BATCH_SEARCH_PROJECT).where(BATCH_SEARCH_PROJECT.SEARCH_UUID.eq(BATCH_SEARCH.UUID))
                )).fetch()
                .stream()
                .map(this::createBatchSearchWithoutQueries)
                .collect(toList())
                .stream().findFirst().orElse(null);


    }

    public Map<String, Integer> getQueries(User user, String batchId, int from, int size, String search, String sort, String order, int maxResults) {
        if(from < 0 || size < 0) {
            throw new IllegalArgumentException("from or size argument cannot be negative");
        }

        DSLContext context = DSL.using(dataSource, dialect);
        SelectConditionStep<Record> statement = context.select().from(BATCH_SEARCH_QUERY).where(BATCH_SEARCH_QUERY.SEARCH_UUID.eq(batchId));
        if (search != null) {
            statement.and(DSL.lower(BATCH_SEARCH_QUERY.QUERY).contains(search.toLowerCase()));
        }
        if (maxResults > -1) {
            statement.and(BATCH_SEARCH_QUERY.QUERY_RESULTS.lessOrEqual(maxResults));
        }
        String sortField = (sort != null) ? sort : BATCH_SEARCH_QUERY.QUERY_NUMBER.getName();
        String sortOrder = (order != null) ? order : SortOrder.ASC.name();

        return statement
                .orderBy(field(sortField).sort(SortOrder.valueOf(sortOrder.toUpperCase())))
                .limit(size > 0 ? size : null)
                .offset(from)
                .fetch().stream().map(r -> new AbstractMap.SimpleEntry<>(r.get("query", String.class), r.get("query_results", Integer.class))).collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> {
                    throw new IllegalStateException(String.format("Duplicate key %s", u));
                }, LinkedHashMap::new));

    }

    @Override
    public Map<String, Integer> getQueries(User user, String batchId, int from, int size, String search, String sort, String order) {
        return getQueries(user, batchId, from, size, search, sort, order, -1);
    }

    @Override
    public boolean reset(String batchId) {
        DSLContext context = DSL.using(dataSource, dialect);
        return context.transactionResult(configuration -> {
            DSLContext inner = DSL.using(configuration);
            UpdateSetMoreStep<org.icij.datashare.db.tables.records.BatchSearchRecord> updateBatchSearch = inner.update(BATCH_SEARCH).set(BATCH_SEARCH.STATE, State.QUEUED.name());
            updateBatchSearch.where(BATCH_SEARCH.UUID.eq(batchId)).execute();
            return inner.deleteFrom(BATCH_SEARCH_RESULT).where(BATCH_SEARCH_RESULT.SEARCH_UUID.eq(batchId)).execute() > 0;

        });

    }

    @Override
    public List<String> getQueued() {
        DSLContext context = DSL.using(dataSource, dialect);
        return context.select(BATCH_SEARCH.UUID).from(BATCH_SEARCH)
                .where(BATCH_SEARCH.STATE.eq(State.QUEUED.name()))
                .fetch(BATCH_SEARCH.UUID);

    }

    @Override
    public List<SearchResult> getResults(final User user, String batchSearchId) {
        return getResults(user, batchSearchId, WebQueryBuilder.createWebQuery().build());
    }

    @Override
    public List<SearchResult> getResults(User user, String batchSearchId, WebQuery webQuery) {
        SelectOnConditionStep<Record> batchSearchUUIDs = using(dataSource, dialect).select().from(BATCH_SEARCH_RESULT).
                join(BATCH_SEARCH).on(BATCH_SEARCH.UUID.equal(BATCH_SEARCH_RESULT.SEARCH_UUID));
        SelectConditionStep<Record> query = batchSearchUUIDs.
                where(BATCH_SEARCH_RESULT.SEARCH_UUID.eq(batchSearchId));
        addFiltersToBatchSearchResultQuery(webQuery, query);
        if (webQuery.size > 0) query.limit(webQuery.size);
        if (webQuery.from > 0) query.offset(webQuery.from);

        return query.fetch().stream().map(r -> createSearchResult(user, r)).collect(toList());

    }

    @Override
    public int getResultsTotal(User user, String batchSearchId, WebQuery webQuery) {
        DSLContext create = DSL.using(dataSource, dialect); SelectOnConditionStep<Record1<String>> batchSearchUUIDs = create.
                select(BATCH_SEARCH_RESULT.SEARCH_UUID).
                from(BATCH_SEARCH_RESULT).
                join(BATCH_SEARCH).on(BATCH_SEARCH.UUID.equal(BATCH_SEARCH_RESULT.SEARCH_UUID));
        SelectConditionStep<Record1<String>> query = batchSearchUUIDs.
                where(BATCH_SEARCH_RESULT.SEARCH_UUID.eq(batchSearchId));
        addFiltersToBatchSearchResultQuery(webQuery, query);
        return create.fetchCount(query);

    }

    @Override
    public boolean publish(User user, String batchId, boolean published) {
        UpdateSetMoreStep<org.icij.datashare.db.tables.records.BatchSearchRecord> updatePublish =  DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.PUBLISHED, published ? 1 : 0);
        return updatePublish.
                where(BATCH_SEARCH.UUID.eq(batchId).
                        and(BATCH_SEARCH.USER_ID.eq(user.id))).execute() > 0;


    }

    private List<BatchSearch> mergeBatchSearches(final List<BatchSearch> flatBatchSearches) {
        Map<String, List<BatchSearch>> collect = flatBatchSearches.stream().collect(groupingBy(bs -> bs.uuid));
        return collect.values().stream().map(batchSearches ->
                        new BatchSearch(batchSearches.get(0).uuid, batchSearches.stream().map(bs -> bs.projects).flatMap(Collection::stream).distinct().collect(toList()), batchSearches.get(0).name, batchSearches.get(0).description,
                                batchSearches.stream().map(bs -> bs.queries.entrySet()).flatMap(Collection::stream).distinct().collect(
                                        toMap(Map.Entry::getKey, Map.Entry::getValue,
                                                (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); },
                                                LinkedHashMap::new)),
                                batchSearches.get(0).nbQueriesWithoutResults, batchSearches.get(0).date, batchSearches.get(0).state,
                                batchSearches.get(0).uri,batchSearches.get(0).user, batchSearches.get(0).nbResults, batchSearches.get(0).published,
                                batchSearches.get(0).fileTypes, batchSearches.get(0).queryTemplate.toString(), batchSearches.get(0).paths, batchSearches.get(0).fuzziness,
                                batchSearches.get(0).phraseMatches, batchSearches.get(0).errorMessage, batchSearches.get(0).errorQuery)).
                sorted(comparing((BatchSearchRecord bs) -> bs.date).reversed()).collect(toList());
    }

    private static void addFiltersToBatchSearchResultQuery(WebQuery webQuery, SelectConditionStep<?> query) {
        if (webQuery.hasFilteredQueries()) {
            if(webQuery.queriesExcluded){
                query.and(BATCH_SEARCH_RESULT.QUERY.notIn(webQuery.queries));
            }else{
                query.and(BATCH_SEARCH_RESULT.QUERY.in(webQuery.queries));
            }
        }
        if(webQuery.hasFilteredContentTypes()){
            query.and(BATCH_SEARCH_RESULT.CONTENT_TYPE.in(webQuery.contentTypes));
        }
        if (webQuery.isSorted()) {
            query.orderBy(field(webQuery.sort + " " + webQuery.order));
        } else {
            query.orderBy(field("query " + webQuery.order), field(DEFAULT_SORT_FIELD + " " + webQuery.order));
        }
    }

    private SelectJoinStep<Record21<String, String, String, String, String, LocalDateTime, String, Integer, String, String, String,Integer, Integer, Integer, String, String, Integer, String, String, Integer, Integer>>
    createBatchSearchWithQueriesSelectStatement(DSLContext create) {
        return create.select(
                        BATCH_SEARCH.UUID,
                        BATCH_SEARCH.NAME,
                        BATCH_SEARCH.DESCRIPTION,
                        BATCH_SEARCH.URI,
                        BATCH_SEARCH.USER_ID,
                        BATCH_SEARCH.BATCH_DATE,
                        BATCH_SEARCH.STATE,
                        BATCH_SEARCH.PUBLISHED,
                        BATCH_SEARCH.FILE_TYPES,
                        BATCH_SEARCH.QUERY_TEMPLATE,
                        BATCH_SEARCH.PATHS,
                        BATCH_SEARCH.FUZZINESS,
                        BATCH_SEARCH.PHRASE_MATCHES,
                        BATCH_SEARCH.BATCH_RESULTS,
                        BATCH_SEARCH.ERROR_MESSAGE,
                        BATCH_SEARCH.ERROR_QUERY,
                        BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS,
                        BATCH_SEARCH_PROJECT.PRJ_ID,
                        BATCH_SEARCH_QUERY.QUERY,
                        BATCH_SEARCH_QUERY.QUERY_NUMBER,
                        BATCH_SEARCH_QUERY.QUERY_RESULTS).
                from(BATCH_SEARCH.join(BATCH_SEARCH_QUERY).on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_QUERY.SEARCH_UUID))
                        .join(BATCH_SEARCH_PROJECT).on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_PROJECT.SEARCH_UUID)));
    }

    private SelectJoinStep<Record19<String, String, String, String, String, LocalDateTime, String, Integer, String, String, String, Integer, Integer, Integer, String, String, String, Integer, Integer>>
    createBatchSearchWithoutQueriesSelectStatement(DSLContext create) {
        return create.select(
                        BATCH_SEARCH.UUID,
                        BATCH_SEARCH.NAME,
                        BATCH_SEARCH.DESCRIPTION,
                        BATCH_SEARCH.URI,
                        BATCH_SEARCH.USER_ID,
                        BATCH_SEARCH.BATCH_DATE,
                        BATCH_SEARCH.STATE,
                        BATCH_SEARCH.PUBLISHED,
                        BATCH_SEARCH.FILE_TYPES,
                        BATCH_SEARCH.QUERY_TEMPLATE,
                        BATCH_SEARCH.PATHS,
                        BATCH_SEARCH.FUZZINESS,
                        BATCH_SEARCH.PHRASE_MATCHES,
                        BATCH_SEARCH.BATCH_RESULTS,
                        BATCH_SEARCH.ERROR_MESSAGE,
                        BATCH_SEARCH.ERROR_QUERY,
                        groupConcat(BATCH_SEARCH_PROJECT.PRJ_ID).as(field("projects")),
                        BATCH_SEARCH.NB_QUERIES,
                        BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS)
                .from(BATCH_SEARCH
                        .join(BATCH_SEARCH_PROJECT)
                        .on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_PROJECT.SEARCH_UUID)));
    }

    private SelectOnConditionStep<Record14<String, String, String, String, String, LocalDateTime, String, Integer, Integer, String, String, String, Integer, Integer>>
    createBatchSearchRecordWithQueriesSelectStatement(DSLContext create) {
        return create.select(
                        BATCH_SEARCH.UUID,
                        BATCH_SEARCH.NAME,
                        BATCH_SEARCH.DESCRIPTION,
                        BATCH_SEARCH.URI,
                        BATCH_SEARCH.USER_ID,
                        BATCH_SEARCH.BATCH_DATE,
                        BATCH_SEARCH.STATE,
                        BATCH_SEARCH.PUBLISHED,
                        BATCH_SEARCH.BATCH_RESULTS,
                        BATCH_SEARCH.ERROR_MESSAGE,
                        BATCH_SEARCH.ERROR_QUERY,
                        groupConcat(BATCH_SEARCH_PROJECT.PRJ_ID).as(field("projects")),
                        BATCH_SEARCH.NB_QUERIES,
                        BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS).
                from(BATCH_SEARCH).join(BATCH_SEARCH_PROJECT).on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_PROJECT.SEARCH_UUID));
    }

    private BatchSearch createBatchSearchFrom(final Record record) {
        Integer query_results = record.getValue(BATCH_SEARCH_QUERY.QUERY_RESULTS);
        Integer nb_queries = query_results == null ? 0: query_results;
        boolean phraseMatches= record.get(BATCH_SEARCH.PHRASE_MATCHES) != 0;
        int nbQueriesWithoutResults = Optional.ofNullable(record.getValue(BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS)).orElse(-1);
        return new BatchSearch(record.get(BATCH_SEARCH.UUID).trim(),
                singletonList(proxy(record.get(BATCH_SEARCH_PROJECT.PRJ_ID))),
                record.getValue(BATCH_SEARCH.NAME),
                record.getValue(BATCH_SEARCH.DESCRIPTION),
                new LinkedHashMap<>() {{
                    put(record.getValue(BATCH_SEARCH_QUERY.QUERY), nb_queries);
                }},
                nbQueriesWithoutResults,
                Date.from(record.get(BATCH_SEARCH.BATCH_DATE).toInstant(ZoneOffset.UTC)),
                State.valueOf(record.get(BATCH_SEARCH.STATE)),
                record.get(BATCH_SEARCH.URI),
                new User(record.get(BATCH_SEARCH.USER_ID)),
                record.get(BATCH_SEARCH.BATCH_RESULTS),
                record.get(BATCH_SEARCH.PUBLISHED) > 0,
                getListFromStringOrNull(record.get(BATCH_SEARCH.FILE_TYPES)),
                record.get(BATCH_SEARCH.QUERY_TEMPLATE),
                getListFromStringOrNull(record.get(BATCH_SEARCH.PATHS)),
                record.get(BATCH_SEARCH.FUZZINESS),
                phraseMatches,
                record.get(BATCH_SEARCH.ERROR_MESSAGE),
                record.get(BATCH_SEARCH.ERROR_QUERY));
    }

    private BatchSearch createBatchSearchWithoutQueries(final Record record) {
        String projects = (String) record.get("projects");
        boolean phraseMatches= record.get(BATCH_SEARCH.PHRASE_MATCHES) != 0;
        int nbQueriesWithoutResults = Optional.ofNullable(record.getValue(BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS)).orElse(-1);
        return new BatchSearch(record.get(BATCH_SEARCH.UUID).trim(),
                getProjects(projects),
                record.getValue(BATCH_SEARCH.NAME),
                record.getValue(BATCH_SEARCH.DESCRIPTION),
                record.get(BATCH_SEARCH.NB_QUERIES),
                nbQueriesWithoutResults,
                Date.from(record.get(BATCH_SEARCH.BATCH_DATE).toInstant(ZoneOffset.UTC)),
                State.valueOf(record.get(BATCH_SEARCH.STATE)),
                record.get(BATCH_SEARCH.URI),
                new User(record.get(BATCH_SEARCH.USER_ID)),
                record.get(BATCH_SEARCH.BATCH_RESULTS),
                record.get(BATCH_SEARCH.PUBLISHED) > 0,
                getListFromStringOrNull(record.get(BATCH_SEARCH.FILE_TYPES)),
                record.get(BATCH_SEARCH.QUERY_TEMPLATE),
                getListFromStringOrNull(record.get(BATCH_SEARCH.PATHS)),
                record.get(BATCH_SEARCH.FUZZINESS),
                phraseMatches,
                record.get(BATCH_SEARCH.ERROR_MESSAGE),
                record.get(BATCH_SEARCH.ERROR_QUERY));
    }

    private static List<String> getListFromStringOrNull(String stringList) {
        return stringList == null || stringList.isEmpty() ? null : asList(stringList.split(LIST_SEPARATOR));
    }

    private BatchSearchRecord createBatchSearchRecordFrom(final Record record) {
        int nbQueries = Optional.ofNullable(record.getValue(BATCH_SEARCH.NB_QUERIES)).orElse(0);
        int nbQueriesWithoutResults = Optional.ofNullable(record.getValue(BATCH_SEARCH.NB_QUERIES_WITHOUT_RESULTS)).orElse(-1);
        String projects = (String) record.get("projects");
        org.icij.datashare.db.tables.records.BatchSearchRecord batchSearch = record.into(BATCH_SEARCH);
        return new BatchSearchRecord(batchSearch.getUuid(),
                getProjectNames(projects),
                batchSearch.getName(),
                batchSearch.getDescription(),
                nbQueries,
                nbQueriesWithoutResults,
                Date.from(batchSearch.getBatchDate().toInstant(ZoneOffset.UTC)),
                State.valueOf(batchSearch.getState()),
                batchSearch.getUri(),
                new User(batchSearch.getUserId()),
                batchSearch.getBatchResults(),
                batchSearch.getPublished() > 0,
                batchSearch.getErrorMessage(),
                batchSearch.getErrorQuery());
    }

    private static List<String> getProjectNames(String prj) {
        return prj == null || prj.isEmpty() ? null : stream(prj.split(LIST_SEPARATOR)).sorted().collect(toList());
    }

    private static List<ProjectProxy> getProjects(String prj) {
        return prj == null || prj.isEmpty() ? null : stream(prj.split(LIST_SEPARATOR)).sorted().map(ProjectProxy::new).collect(toList());
    }

    private SearchResult createSearchResult(final User actualUser, final Record record) {
        String owner = record.get(BATCH_SEARCH.USER_ID);
        String prj = record.get(BATCH_SEARCH_RESULT.PRJ_ID);
        boolean published = record.get(BATCH_SEARCH.PUBLISHED)>0;
        if (!actualUser.id.equals(owner) && !published) {
            throw new UnauthorizedUserException(record.get(BATCH_SEARCH.UUID), owner, actualUser.id);
        }
        LocalDateTime creationDate = record.get(BATCH_SEARCH_RESULT.CREATION_DATE);
        return new SearchResult(record.get(BATCH_SEARCH_RESULT.QUERY),
                prj == null || prj.isEmpty() ? null : proxy(prj),
                record.get(BATCH_SEARCH_RESULT.DOC_ID),
                record.getValue(BATCH_SEARCH_RESULT.ROOT_ID),
                Paths.get(record.getValue(BATCH_SEARCH_RESULT.DOC_PATH)),
                creationDate == null ? null: Date.from(creationDate.toInstant(ZoneOffset.UTC)),
                record.getValue(BATCH_SEARCH_RESULT.CONTENT_TYPE),
                record.getValue(BATCH_SEARCH_RESULT.CONTENT_LENGTH),
                record.get(BATCH_SEARCH_RESULT.DOC_NB));
    }

    private static void addFilterToSelectCondition(WebQuery webQuery, SelectConditionStep<? extends Record> query) {
        if(!webQuery.query.isEmpty() && !webQuery.query.equals("*")){
            String searchQuery = String.format("%s%s%s","%", webQuery.query,"%");
            if(webQuery.field.equals("all")) {
                query.and(BATCH_SEARCH.NAME.like(searchQuery).or(BATCH_SEARCH.DESCRIPTION.like(searchQuery)).or(BATCH_SEARCH.USER_ID.like(searchQuery)));
            }
            else {
                stream(BATCH_SEARCH.fields()).filter(field -> field.getName().equals(webQuery.field)).forEach(field -> query.and(field.like(searchQuery)));
            }
        }
        if (webQuery.hasFilteredDates()) {
            query.and(BATCH_SEARCH.BATCH_DATE.between(new Timestamp(Long.parseLong(webQuery.batchDate.get(0))).toLocalDateTime(), new Timestamp(Long.parseLong(webQuery.batchDate.get(1))).toLocalDateTime()));
        }
        if (webQuery.hasFilteredStates()) {
            query.and(BATCH_SEARCH.STATE.in(webQuery.state));
        }
        if (webQuery.hasFilteredPublishStates()) {
            query.and(BATCH_SEARCH.PUBLISHED.eq(Integer.parseInt(webQuery.publishState)));
        }
        if(webQuery.hasFilteredContentTypes()){
            query.and(BATCH_SEARCH.FILE_TYPES.in(webQuery.contentTypes));
        }
    }

    private SelectConditionStep<Record1<String>>  selectBatchSearchWithZeroNbQueries(DSLContext create) {
        return create.select(BATCH_SEARCH.UUID).from(BATCH_SEARCH).where(BATCH_SEARCH.NB_QUERIES.eq(0));
    }

    private void updateBatchSearchNbQueries(DSLContext context, List<String> uuids) {
        if (uuids.isEmpty()){
            return;
        }

        context.select(BATCH_SEARCH_QUERY.SEARCH_UUID, count())
                .from(BATCH_SEARCH_QUERY)
                .where(BATCH_SEARCH_QUERY.SEARCH_UUID.in(uuids))
                .groupBy(BATCH_SEARCH_QUERY.SEARCH_UUID)
                .fetch().forEach(record2-> setNbQueries(context,record2.value1(),record2.value2()) );
    }

    /**
     * This method allows retrocompatibility from the times when
     * the nbQueries were not cached into the database. Each record
     * without the cache is updated on the fly.
     */
    private void cacheNbQueries(WebQuery webQuery, DSLContext context) {
        SelectConditionStep<Record1<String>> zeroNbQueries = selectBatchSearchWithZeroNbQueries(context);
        addFilterToSelectCondition(webQuery, zeroNbQueries);
        List<String> uuids = zeroNbQueries.fetch(BATCH_SEARCH.UUID, String.class);
        updateBatchSearchNbQueries(context, uuids);
    }

    int getNbQueries(String batchSearchUUID){
        DSLContext context = DSL.using(dataSource, dialect);
        Record1<Integer> record = context.select(BATCH_SEARCH.NB_QUERIES).from(BATCH_SEARCH)
                .where(BATCH_SEARCH.UUID.eq(batchSearchUUID)).fetchOne();
        return (int) record.getValue(0);

    }

    boolean setNbQueries(DSLContext context, String batchSearchUuid, int nbQueries){
        UpdateSetMoreStep<org.icij.datashare.db.tables.records.BatchSearchRecord> updateNbQueries = context.update(BATCH_SEARCH).set(BATCH_SEARCH.NB_QUERIES, nbQueries);
        return updateNbQueries.where(BATCH_SEARCH.UUID.eq(batchSearchUuid)).execute() > 0;

    }

    boolean resetNbQueries(String batchSearchUuid){
        DSLContext context = DSL.using(dataSource, dialect);
        return setNbQueries(context, batchSearchUuid, 0);

    }

    @Override
    public void close() throws IOException {
        if (dataSource instanceof Closeable) {
            ((Closeable) dataSource).close();
        }
    }

    public static class UnauthorizedUserException extends RuntimeException {
        public UnauthorizedUserException(String searchId, String owner, String actualUser) {
            super("user " + actualUser + " requested results for search " + searchId + " that belongs to user " + owner);
        }
    }

    public static class BatchNotFoundException extends RuntimeException {
        public BatchNotFoundException(String searchId) {
            super("no batch search with id=" + searchId + " found in database");
        }
    }
}
