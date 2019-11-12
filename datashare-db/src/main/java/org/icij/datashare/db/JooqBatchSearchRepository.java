package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearch.State;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.db.tables.records.BatchSearchQueryRecord;
import org.icij.datashare.db.tables.records.BatchSearchResultRecord;
import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.IntStream;

import static java.lang.String.join;
import static java.util.Arrays.asList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.*;
import static org.icij.datashare.batch.BatchSearchRepository.WebQuery.DEFAULT_SORT_FIELD;
import static org.icij.datashare.db.tables.BatchSearch.BATCH_SEARCH;
import static org.icij.datashare.db.tables.BatchSearchQuery.BATCH_SEARCH_QUERY;
import static org.icij.datashare.db.tables.BatchSearchResult.BATCH_SEARCH_RESULT;
import static org.icij.datashare.text.Project.project;
import static org.jooq.impl.DSL.*;

public class JooqBatchSearchRepository implements BatchSearchRepository {
    private static final String LIST_SEPARATOR = ",";
    private final DataSource dataSource;
    private final SQLDialect dialect;

    JooqBatchSearchRepository(final DataSource dataSource, final SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public boolean save(final BatchSearch batchSearch) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.insertInto(BATCH_SEARCH, BATCH_SEARCH.UUID, BATCH_SEARCH.NAME, BATCH_SEARCH.DESCRIPTION, BATCH_SEARCH.USER_ID,
                    BATCH_SEARCH.PRJ_ID, BATCH_SEARCH.BATCH_DATE, BATCH_SEARCH.STATE, BATCH_SEARCH.PUBLISHED, BATCH_SEARCH.FILE_TYPES,
                    BATCH_SEARCH.PATHS, BATCH_SEARCH.FUZZINESS, BATCH_SEARCH.PHRASE_MATCHES).
                    values(batchSearch.uuid, batchSearch.name, batchSearch.description, batchSearch.user.id,
                            batchSearch.project.getId(), new Timestamp(batchSearch.getDate().getTime()), batchSearch.state.name(), batchSearch.published?1:0,
                            join(LIST_SEPARATOR, batchSearch.fileTypes),join(LIST_SEPARATOR, batchSearch.paths), batchSearch.fuzziness,batchSearch.phraseMatches?1:0).execute();

            InsertValuesStep4<BatchSearchQueryRecord, String, String, Integer, Integer> insertQuery = inner.insertInto(BATCH_SEARCH_QUERY, BATCH_SEARCH_QUERY.SEARCH_UUID, BATCH_SEARCH_QUERY.QUERY, BATCH_SEARCH_QUERY.QUERY_NUMBER, BATCH_SEARCH_QUERY.QUERY_RESULTS);
            List<String> queries = new ArrayList<>(batchSearch.queries.keySet());
            IntStream.range(0, queries.size()).forEach(i -> insertQuery.values(batchSearch.uuid, queries.get(i), i, 0));
            return insertQuery.execute() > 0;
        });
    }

    @Override
    public boolean saveResults(String batchSearchId, String query, List<Document> documents) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.update(BATCH_SEARCH_QUERY).set(BATCH_SEARCH_QUERY.QUERY_RESULTS,
                    BATCH_SEARCH_QUERY.QUERY_RESULTS.plus(documents.size())).
                    where(BATCH_SEARCH_QUERY.SEARCH_UUID.eq(batchSearchId).
                            and(BATCH_SEARCH_QUERY.QUERY.eq(query))).execute();

            inner.update(BATCH_SEARCH).set(BATCH_SEARCH.BATCH_RESULTS,
                    BATCH_SEARCH.BATCH_RESULTS.plus(documents.size())).
                    where(BATCH_SEARCH.UUID.eq(batchSearchId)).execute();

            InsertValuesStep9<BatchSearchResultRecord, String, String, Integer, String, String, String, Timestamp, String, Long> insertQuery =
                    inner.insertInto(BATCH_SEARCH_RESULT, BATCH_SEARCH_RESULT.SEARCH_UUID, BATCH_SEARCH_RESULT.QUERY, BATCH_SEARCH_RESULT.DOC_NB,
                    BATCH_SEARCH_RESULT.DOC_ID, BATCH_SEARCH_RESULT.ROOT_ID, BATCH_SEARCH_RESULT.DOC_NAME, BATCH_SEARCH_RESULT.CREATION_DATE,
                    BATCH_SEARCH_RESULT.CONTENT_TYPE, BATCH_SEARCH_RESULT.CONTENT_LENGTH);
            IntStream.range(0, documents.size()).forEach(i -> insertQuery.values(batchSearchId, query, i,
                                documents.get(i).getId(), documents.get(i).getRootDocument(), documents.get(i).getPath().getFileName().toString(),
                                documents.get(i).getCreationDate() == null ? (Timestamp) null:
                                        new Timestamp(documents.get(i).getCreationDate().getTime()),
                                documents.get(i).getContentType(), documents.get(i).getContentLength()));
            return insertQuery.execute() > 0;
        });
    }

    @Override
    public boolean setState(String batchSearchId, State state) {
        return DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.STATE, state.name()).
                where(BATCH_SEARCH.UUID.eq(batchSearchId)).execute() > 0;
    }

    @Override
    public boolean setState(String batchSearchId, SearchException error) {
        return DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.STATE, State.FAILURE.name()).
                set(BATCH_SEARCH.ERROR_MESSAGE, error.toString()).
                where(BATCH_SEARCH.UUID.eq(batchSearchId)).execute() > 0;
    }

    @Override
    public boolean deleteAll(User user) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.deleteFrom(BATCH_SEARCH_QUERY).where(BATCH_SEARCH_QUERY.SEARCH_UUID.
                    in(select(BATCH_SEARCH.UUID).from(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)))).
                    execute();
            inner.deleteFrom(BATCH_SEARCH_RESULT).where(BATCH_SEARCH_RESULT.SEARCH_UUID.
                    in(select(BATCH_SEARCH.UUID).from(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)))).
                    execute();
            return inner.deleteFrom(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)).execute() > 0;
        });
    }

    @Override
    public boolean delete(User user, String batchId) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            SelectConditionStep<Record1<String>> batch_uuid = select(BATCH_SEARCH.UUID).from(BATCH_SEARCH).
                    where(BATCH_SEARCH.USER_ID.eq(user.id)).and(BATCH_SEARCH.UUID.eq(batchId));
            inner.deleteFrom(BATCH_SEARCH_QUERY).where(BATCH_SEARCH_QUERY.SEARCH_UUID.in(batch_uuid)).execute();
            inner.deleteFrom(BATCH_SEARCH_RESULT).where(BATCH_SEARCH_RESULT.SEARCH_UUID.in(batch_uuid)).execute();
            return inner.deleteFrom(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)).
                    and(BATCH_SEARCH.UUID.eq(batchId)).execute() > 0;
        });
    }

    @Override
    public List<BatchSearch> get(final User user) {
        return mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
                where(BATCH_SEARCH.USER_ID.eq(user.id).
                        or(BATCH_SEARCH.PUBLISHED.greaterThan(0))).
                        orderBy(BATCH_SEARCH.BATCH_DATE.desc(), BATCH_SEARCH_QUERY.QUERY_NUMBER).
                fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
    }

    @Override
    public List<BatchSearch> get(User user, List<String> projectsIds) {
        return mergeBatchSearches(
               createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
               where(BATCH_SEARCH.PRJ_ID.in(projectsIds).
                       and(BATCH_SEARCH.USER_ID.eq(user.id).
                               or(BATCH_SEARCH.PUBLISHED.greaterThan(0)))).
                       orderBy(BATCH_SEARCH.BATCH_DATE.desc(), BATCH_SEARCH_QUERY.QUERY_NUMBER).
               fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
    }

    @Override
    public BatchSearch get(User user, String batchId) {
        return mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
                        where(BATCH_SEARCH.UUID.eq(batchId)).
                        fetch().stream().map(this::createBatchSearchFrom).collect(toList())).get(0);
    }

    @Override
    public List<BatchSearch> getQueued() {
        return mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
                where(BATCH_SEARCH.STATE.eq(State.QUEUED.name())).
                        orderBy(BATCH_SEARCH.BATCH_DATE.desc(), BATCH_SEARCH_QUERY.QUERY_NUMBER).
                fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
    }

    @Override
    public List<SearchResult> getResults(final User user, String batchSearchId) {
        return getResults(user, batchSearchId, new WebQuery(0, 0));
    }

    @Override
    public List<SearchResult> getResults(User user, String batchSearchId, WebQuery webQuery) {
        DSLContext create = DSL.using(dataSource, dialect);
        SelectConditionStep<Record> query = create.select().from(BATCH_SEARCH_RESULT).
                join(BATCH_SEARCH).on(BATCH_SEARCH.UUID.equal(BATCH_SEARCH_RESULT.SEARCH_UUID)).
                where(BATCH_SEARCH_RESULT.SEARCH_UUID.eq(batchSearchId));
        if (webQuery.hasFilteredQueries()) query.and(BATCH_SEARCH_RESULT.QUERY.in(webQuery.queries));
        if (webQuery.isSorted()) {
            query.orderBy(field(webQuery.sort + " " + webQuery.order));
        } else {
            query.orderBy(field("query " + webQuery.order), field(DEFAULT_SORT_FIELD + " " + webQuery.order));
        }
        if (webQuery.size > 0) query.limit(webQuery.size);
        if (webQuery.from > 0) query.offset(webQuery.from);

        return query.fetch().stream().map(r -> createSearchResult(user, r)).collect(toList());
    }

    @Override
    public boolean publish(User user, String batchId, boolean published) {
        return DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.PUBLISHED, published?1:0).
                where(BATCH_SEARCH.UUID.eq(batchId).
                        and(BATCH_SEARCH.USER_ID.eq(user.id))).execute() > 0;
    }

    private List<BatchSearch> mergeBatchSearches(final List<BatchSearch> flatBatchSearches) {
        Map<String, List<BatchSearch>> collect = flatBatchSearches.stream().collect(groupingBy(bs -> bs.uuid));
        return collect.values().stream().map(batchSearches ->
                new BatchSearch(batchSearches.get(0).uuid, batchSearches.get(0).project, batchSearches.get(0).name, batchSearches.get(0).description,
                        batchSearches.stream().map(bs -> bs.queries.entrySet()).flatMap(Collection::stream).collect(
                                toMap(Map.Entry::getKey, Map.Entry::getValue,
                                        (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); },
                                        LinkedHashMap::new)),
                        batchSearches.get(0).getDate(),
                        batchSearches.get(0).state, batchSearches.get(0).user, batchSearches.get(0).nbResults, batchSearches.get(0).published,
                        batchSearches.get(0).fileTypes, batchSearches.get(0).paths, batchSearches.get(0).fuzziness,
                        batchSearches.get(0).phraseMatches, batchSearches.get(0).errorMessage)).
                sorted(comparing(BatchSearch::getDate).reversed()).collect(toList());
    }

    private SelectJoinStep<Record17<String, String, String, String, String, Timestamp, String, Integer, String, String, Integer, Integer, Integer, String, String, Integer, Integer>>
    createBatchSearchWithQueriesSelectStatement(DSLContext create) {
        return create.select(
                BATCH_SEARCH.UUID,
                BATCH_SEARCH.NAME,
                BATCH_SEARCH.DESCRIPTION,
                BATCH_SEARCH.USER_ID,
                BATCH_SEARCH.PRJ_ID,
                BATCH_SEARCH.BATCH_DATE,
                BATCH_SEARCH.STATE,
                BATCH_SEARCH.PUBLISHED,
                BATCH_SEARCH.FILE_TYPES,
                BATCH_SEARCH.PATHS,
                BATCH_SEARCH.FUZZINESS,
                BATCH_SEARCH.PHRASE_MATCHES,
                BATCH_SEARCH.BATCH_RESULTS,
                BATCH_SEARCH.ERROR_MESSAGE,
                BATCH_SEARCH_QUERY.QUERY,
                BATCH_SEARCH_QUERY.QUERY_NUMBER,
                BATCH_SEARCH_QUERY.QUERY_RESULTS).
                from(BATCH_SEARCH.join(BATCH_SEARCH_QUERY).on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_QUERY.SEARCH_UUID)));
    }

    private BatchSearch createBatchSearchFrom(final Record record) {
        Integer query_results = record.getValue(BATCH_SEARCH_QUERY.QUERY_RESULTS);
        Integer nb_queries = query_results == null ? 0: query_results;
        String file_types = record.get(BATCH_SEARCH.FILE_TYPES);
        String paths = record.get(BATCH_SEARCH.PATHS);
        boolean phraseMatches=record.get(BATCH_SEARCH.PHRASE_MATCHES)==0?false:true ;
        return new BatchSearch(record.get(BATCH_SEARCH.UUID).trim(),
                project(record.getValue(BATCH_SEARCH.PRJ_ID)),
                record.getValue(BATCH_SEARCH.NAME),
                record.getValue(BATCH_SEARCH.DESCRIPTION),
                new LinkedHashMap<String, Integer>() {{
                    put(record.getValue(BATCH_SEARCH_QUERY.QUERY), nb_queries);}},
                Date.from(record.get(BATCH_SEARCH.BATCH_DATE).toInstant()),
                State.valueOf(record.get(BATCH_SEARCH.STATE)),
                new User(record.get(BATCH_SEARCH.USER_ID)),
                record.get(BATCH_SEARCH.BATCH_RESULTS),
                record.get(BATCH_SEARCH.PUBLISHED) > 0,
                file_types == null || file_types.isEmpty()? null: asList(file_types.split(LIST_SEPARATOR)),
                paths == null || paths.isEmpty()? null: asList(paths.split(LIST_SEPARATOR)),
                record.get(BATCH_SEARCH.FUZZINESS),
                phraseMatches,
                record.get(BATCH_SEARCH.ERROR_MESSAGE));
    }

    private SearchResult createSearchResult(final User actualUser, final Record record) {
        String owner = record.get(BATCH_SEARCH.USER_ID);
        boolean published = record.get(BATCH_SEARCH.PUBLISHED)>0;
        if (!actualUser.id.equals(owner) && !published)
            throw new UnauthorizedUserException(record.get(BATCH_SEARCH.UUID), owner, actualUser.id);
        Timestamp creationDate = record.get(BATCH_SEARCH_RESULT.CREATION_DATE);
        return new SearchResult(record.get(BATCH_SEARCH_RESULT.QUERY),
                record.get(BATCH_SEARCH_RESULT.DOC_ID),
                record.getValue(BATCH_SEARCH_RESULT.ROOT_ID),
                record.getValue(BATCH_SEARCH_RESULT.DOC_NAME),
                creationDate == null ? null: new Date(creationDate.getTime()),
                record.getValue(BATCH_SEARCH_RESULT.CONTENT_TYPE),
                record.getValue(BATCH_SEARCH_RESULT.CONTENT_LENGTH),
                record.get(BATCH_SEARCH_RESULT.DOC_NB));
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
}
