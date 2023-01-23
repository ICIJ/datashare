package org.icij.datashare.db;

import org.icij.datashare.batch.*;
import org.icij.datashare.batch.BatchSearchRecord.State;
import org.icij.datashare.db.tables.records.BatchSearchProjectRecord;
import org.icij.datashare.db.tables.records.BatchSearchQueryRecord;
import org.icij.datashare.db.tables.records.BatchSearchResultRecord;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Timestamp;
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
                    BATCH_SEARCH.BATCH_DATE, BATCH_SEARCH.STATE, BATCH_SEARCH.PUBLISHED, BATCH_SEARCH.FILE_TYPES,
                    BATCH_SEARCH.PATHS, BATCH_SEARCH.FUZZINESS, BATCH_SEARCH.PHRASE_MATCHES).
                    values(batchSearch.uuid, batchSearch.name, batchSearch.description, batchSearch.user.id,
                            new Timestamp(batchSearch.getDate().getTime()), batchSearch.state.name(), batchSearch.published?1:0,
                            join(LIST_SEPARATOR, batchSearch.fileTypes),join(LIST_SEPARATOR, batchSearch.paths), batchSearch.fuzziness,batchSearch.phraseMatches?1:0).execute();

            InsertValuesStep4<BatchSearchQueryRecord, String, String, Integer, Integer> insertQuery = inner.insertInto(BATCH_SEARCH_QUERY, BATCH_SEARCH_QUERY.SEARCH_UUID, BATCH_SEARCH_QUERY.QUERY, BATCH_SEARCH_QUERY.QUERY_NUMBER, BATCH_SEARCH_QUERY.QUERY_RESULTS);
            List<String> queries = new ArrayList<>(batchSearch.queries.keySet());
            IntStream.range(0, queries.size()).forEach(i -> insertQuery.values(batchSearch.uuid, queries.get(i), i, 0));
            InsertValuesStep2<BatchSearchProjectRecord, String, String> insertProject = inner.insertInto(BATCH_SEARCH_PROJECT, BATCH_SEARCH_PROJECT.SEARCH_UUID, BATCH_SEARCH_PROJECT.PRJ_ID);
            batchSearch.projects.forEach(project -> insertProject.values(batchSearch.uuid, project.getId()));
            return insertQuery.execute() + insertProject.execute() > 0;
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

            InsertValuesStep10<BatchSearchResultRecord, String, String, Integer, String, String, String, Timestamp, String, Long, String> insertQuery =
                    inner.insertInto(BATCH_SEARCH_RESULT, BATCH_SEARCH_RESULT.SEARCH_UUID, BATCH_SEARCH_RESULT.QUERY, BATCH_SEARCH_RESULT.DOC_NB,
                    BATCH_SEARCH_RESULT.DOC_ID, BATCH_SEARCH_RESULT.ROOT_ID, BATCH_SEARCH_RESULT.DOC_PATH, BATCH_SEARCH_RESULT.CREATION_DATE,
                    BATCH_SEARCH_RESULT.CONTENT_TYPE, BATCH_SEARCH_RESULT.CONTENT_LENGTH, BATCH_SEARCH_RESULT.PRJ_ID);
            IntStream.range(0, documents.size()).forEach(i -> insertQuery.values(batchSearchId, query, i,
                                documents.get(i).getId(), documents.get(i).getRootDocument(), documents.get(i).getPath().toString(),
                                documents.get(i).getCreationDate() == null ? (Timestamp) null:
                                        new Timestamp(documents.get(i).getCreationDate().getTime()),
                                documents.get(i).getContentType(), documents.get(i).getContentLength(), documents.get(i).getProject().getId()));
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
                set(BATCH_SEARCH.ERROR_QUERY, error.query).
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
            inner.deleteFrom(BATCH_SEARCH_PROJECT).where(BATCH_SEARCH_PROJECT.SEARCH_UUID.
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
            inner.deleteFrom(BATCH_SEARCH_PROJECT).where(BATCH_SEARCH_PROJECT.SEARCH_UUID.in(batch_uuid)).execute();
            return inner.deleteFrom(BATCH_SEARCH).where(BATCH_SEARCH.USER_ID.eq(user.id)).
                    and(BATCH_SEARCH.UUID.eq(batchId)).and(BATCH_SEARCH.STATE.ne(State.RUNNING.name())).execute() > 0;
        });
    }

    @Override
    public BatchSearch get(String id) {
        Optional<BatchSearch> batchSearches = mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(using(dataSource, dialect)).where(BATCH_SEARCH.UUID.eq(id)).
                        fetch().stream().map(this::createBatchSearchFrom).collect(toList())).stream().findFirst();
        return batchSearches.orElseThrow(() -> new BatchNotFoundException(id));
    }

    @Override
    public int getTotal(User user, List<String> projectsIds, WebQuery webQuery) {
        SelectConditionStep<Record1<Integer>> query = using(dataSource, dialect).select(countDistinct(BATCH_SEARCH_PROJECT.SEARCH_UUID)).
                from(BATCH_SEARCH_PROJECT).join(BATCH_SEARCH).on(BATCH_SEARCH_PROJECT.SEARCH_UUID.equal(BATCH_SEARCH.UUID)).
                where(BATCH_SEARCH.USER_ID.eq(user.id).or(BATCH_SEARCH.PUBLISHED.greaterThan(0)));
        addFilterToSelectCondition(webQuery, query);
        return query.groupBy(BATCH_SEARCH.UUID).having(count().eq(
                selectCount().from(BATCH_SEARCH_PROJECT)
                        .where(BATCH_SEARCH_PROJECT.SEARCH_UUID.eq(BATCH_SEARCH.UUID).and(BATCH_SEARCH_PROJECT.PRJ_ID.in(projectsIds)))
        )).fetch(0,int.class).stream().reduce(0, Integer::sum);
    }

    @Override
    public List<BatchSearchRecord> getRecords(User user, List<String> projectsIds) {
        return getRecords(user, projectsIds, new WebQuery());
    }

    @Override
    public List<BatchSearchRecord> getRecords(User user, List<String> projectsIds, WebQuery webQuery) {
        SelectConditionStep<Record12<String, String, String, String, Timestamp, String, Integer, Integer, String, String, String, Object>> query = createBatchSearchRecordWithQueriesSelectStatement(using(dataSource, dialect))
                .where(BATCH_SEARCH.USER_ID.eq(user.id).or(BATCH_SEARCH.PUBLISHED.greaterThan(0)));
        List<String> filteredProjects = webQuery.hasFilteredProjects() ? webQuery.project : projectsIds;
        addFilterToSelectCondition(webQuery, query);
        if (webQuery.isSorted()) {
            query.orderBy(field(webQuery.sort + " " + webQuery.order));
        } else {
            query.orderBy(field("batch_date desc"));
        }
        if (webQuery.size > 0) query.limit(webQuery.size);
        if (webQuery.from > 0) query.offset(webQuery.from);
        return query.groupBy(BATCH_SEARCH.UUID).having(count().eq(
                        selectCount().from(BATCH_SEARCH_PROJECT)
                                     .where(BATCH_SEARCH_PROJECT.SEARCH_UUID.eq(BATCH_SEARCH.UUID).and(BATCH_SEARCH_PROJECT.PRJ_ID.in(filteredProjects)))
                )).orderBy(BATCH_SEARCH.BATCH_DATE.desc()).
                fetch().stream().map(this::createBatchSearchRecordFrom).collect(toList());
    }

    @Override
    public BatchSearch get(User user, String batchId) {
        return mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
                        where(BATCH_SEARCH.UUID.eq(batchId)).
                        fetch().stream().map(this::createBatchSearchFrom).collect(toList())).get(0);
    }

    @Override
    public BatchSearch get(User user, String batchId, boolean withQueries) {
        if (withQueries){
            return get(user, batchId);
        }
        return createBatchSearchWithoutQueriesSelectStatement(DSL.using(dataSource, dialect)).
                where(BATCH_SEARCH.UUID.eq(batchId)).groupBy(BATCH_SEARCH.UUID).having(count().eq(
                        selectCount().from(BATCH_SEARCH_PROJECT)
                                .where(BATCH_SEARCH_PROJECT.SEARCH_UUID.eq(BATCH_SEARCH.UUID))))
                .fetch().stream().map(this::createBatchSearchWithoutQueries).collect(toList()).get(0);
    }

    @Override
    public Map<String, Integer> getQueries(User user, String batchId, int from, int size, String search, String orderBy ) {
        if(from < 0 || size < 0) {
            throw new IllegalArgumentException("from or size argument cannot be negative");
        }
        SelectConditionStep<Record> statement = using(dataSource, dialect)
                .select()
                .from(BATCH_SEARCH_QUERY)
                .where(BATCH_SEARCH_QUERY.SEARCH_UUID.eq(batchId));
        if (search != null) {
            statement.and(BATCH_SEARCH_QUERY.QUERY.contains(search));
        }
        if (orderBy != null) {
            statement.orderBy(field(orderBy).asc());
        } else {
            statement.orderBy(BATCH_SEARCH_QUERY.QUERY_NUMBER.asc());
        }

        if (size > 0) {
            statement.limit(size);
        }
        statement.offset(from);

        return statement.fetch().stream().map(
                r -> new AbstractMap.SimpleEntry<>(r.get("query", String.class), r.get("query_results", Integer.class))).collect(
                toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); },
                        LinkedHashMap::new));
    }

    @Override
    public boolean reset(String batchId) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.update(BATCH_SEARCH).set(BATCH_SEARCH.STATE, State.QUEUED.name()).where(BATCH_SEARCH.UUID.eq(batchId)).execute();
            return inner.deleteFrom(BATCH_SEARCH_RESULT).where(BATCH_SEARCH_RESULT.SEARCH_UUID.eq(batchId)).execute() > 0;
        });
    }

    @Override
    public List<String> getQueued() {
        return  DSL.using(dataSource,dialect).select(BATCH_SEARCH.UUID).from(BATCH_SEARCH)
                .where(BATCH_SEARCH.STATE.eq(State.QUEUED.name()))
                .fetch(BATCH_SEARCH.UUID);
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
                new BatchSearch(batchSearches.get(0).uuid, batchSearches.stream().map(bs -> bs.projects).flatMap(Collection::stream).distinct().collect(toList()), batchSearches.get(0).name, batchSearches.get(0).description,
                        (LinkedHashMap<String, Integer>) batchSearches.stream().map(bs -> bs.queries.entrySet()).flatMap(Collection::stream).distinct().collect(
                                toMap(Map.Entry::getKey, Map.Entry::getValue,
                                        (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); },
                                        LinkedHashMap::new)),
                        batchSearches.get(0).getDate(),
                        batchSearches.get(0).state, batchSearches.get(0).user, batchSearches.get(0).nbResults, batchSearches.get(0).published,
                        batchSearches.get(0).fileTypes, batchSearches.get(0).paths, batchSearches.get(0).fuzziness,
                        batchSearches.get(0).phraseMatches, batchSearches.get(0).errorMessage, batchSearches.get(0).errorQuery)).
                sorted(comparing(BatchSearch::getDate).reversed()).collect(toList());
    }

    private SelectJoinStep<Record18<String, String, String, String, Timestamp, String, Integer, String, String, Integer, Integer, Integer, String, String, String, String, Integer, Integer>>
    createBatchSearchWithQueriesSelectStatement(DSLContext create) {
        return create.select(
                BATCH_SEARCH.UUID,
                BATCH_SEARCH.NAME,
                BATCH_SEARCH.DESCRIPTION,
                BATCH_SEARCH.USER_ID,
                BATCH_SEARCH.BATCH_DATE,
                BATCH_SEARCH.STATE,
                BATCH_SEARCH.PUBLISHED,
                BATCH_SEARCH.FILE_TYPES,
                BATCH_SEARCH.PATHS,
                BATCH_SEARCH.FUZZINESS,
                BATCH_SEARCH.PHRASE_MATCHES,
                BATCH_SEARCH.BATCH_RESULTS,
                BATCH_SEARCH.ERROR_MESSAGE,
                BATCH_SEARCH.ERROR_QUERY,
                BATCH_SEARCH_PROJECT.PRJ_ID,
                BATCH_SEARCH_QUERY.QUERY,
                BATCH_SEARCH_QUERY.QUERY_NUMBER,
                BATCH_SEARCH_QUERY.QUERY_RESULTS).
                from(BATCH_SEARCH.join(BATCH_SEARCH_QUERY).on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_QUERY.SEARCH_UUID))
                        .join(BATCH_SEARCH_PROJECT).on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_PROJECT.SEARCH_UUID)));
    }

    private SelectJoinStep<Record16<String, String, String, String, Timestamp, String, Integer, String, String, Integer, Integer, Integer, String, String, String, Integer>>
    createBatchSearchWithoutQueriesSelectStatement(DSLContext create) {
        return create.select(
                        BATCH_SEARCH.UUID,
                        BATCH_SEARCH.NAME,
                        BATCH_SEARCH.DESCRIPTION,
                        BATCH_SEARCH.USER_ID,
                        BATCH_SEARCH.BATCH_DATE,
                        BATCH_SEARCH.STATE,
                        BATCH_SEARCH.PUBLISHED,
                        BATCH_SEARCH.FILE_TYPES,
                        BATCH_SEARCH.PATHS,
                        BATCH_SEARCH.FUZZINESS,
                        BATCH_SEARCH.PHRASE_MATCHES,
                        BATCH_SEARCH.BATCH_RESULTS,
                        BATCH_SEARCH.ERROR_MESSAGE,
                        BATCH_SEARCH.ERROR_QUERY,
                        groupConcat(BATCH_SEARCH_PROJECT.PRJ_ID).as(field("projects")),
                        field(selectCount()
                                .from(BATCH_SEARCH_QUERY)
                                .where(BATCH_SEARCH_QUERY.SEARCH_UUID.eq(BATCH_SEARCH.UUID)))
                                .as(field("nb_queries")))
                .from(BATCH_SEARCH
                        .join(BATCH_SEARCH_PROJECT)
                        .on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_PROJECT.SEARCH_UUID)));
    }

    private SelectOnConditionStep<Record12<String, String, String, String, Timestamp, String, Integer, Integer, String, String, String, Object>>
    createBatchSearchRecordWithQueriesSelectStatement(DSLContext create) {
        return create.select(
                BATCH_SEARCH.UUID,
                BATCH_SEARCH.NAME,
                BATCH_SEARCH.DESCRIPTION,
                BATCH_SEARCH.USER_ID,
                BATCH_SEARCH.BATCH_DATE,
                BATCH_SEARCH.STATE,
                BATCH_SEARCH.PUBLISHED,
                BATCH_SEARCH.BATCH_RESULTS,
                BATCH_SEARCH.ERROR_MESSAGE,
                BATCH_SEARCH.ERROR_QUERY,
                groupConcat(BATCH_SEARCH_PROJECT.PRJ_ID).as(field("projects")),
                create.selectCount().from(BATCH_SEARCH_QUERY).where(BATCH_SEARCH_QUERY.SEARCH_UUID.eq(BATCH_SEARCH.UUID)).asField("nbQueries")).
                from(BATCH_SEARCH).join(BATCH_SEARCH_PROJECT).on(BATCH_SEARCH.UUID.eq(BATCH_SEARCH_PROJECT.SEARCH_UUID));
    }

    private BatchSearch createBatchSearchFrom(final Record record) {
        Integer query_results = record.getValue(BATCH_SEARCH_QUERY.QUERY_RESULTS);
        Integer nb_queries = query_results == null ? 0: query_results;
        boolean phraseMatches= record.get(BATCH_SEARCH.PHRASE_MATCHES) != 0;
        return new BatchSearch(record.get(BATCH_SEARCH.UUID).trim(),
                singletonList(project(record.get(BATCH_SEARCH_PROJECT.PRJ_ID))),
                record.getValue(BATCH_SEARCH.NAME),
                record.getValue(BATCH_SEARCH.DESCRIPTION),
                new LinkedHashMap<String, Integer>() {{
                    put(record.getValue(BATCH_SEARCH_QUERY.QUERY), nb_queries);}},
                Date.from(record.get(BATCH_SEARCH.BATCH_DATE).toInstant()),
                State.valueOf(record.get(BATCH_SEARCH.STATE)),
                new User(record.get(BATCH_SEARCH.USER_ID)),
                record.get(BATCH_SEARCH.BATCH_RESULTS),
                record.get(BATCH_SEARCH.PUBLISHED) > 0,
                getListFromStringOrNull(record.get(BATCH_SEARCH.FILE_TYPES)),
                getListFromStringOrNull(record.get(BATCH_SEARCH.PATHS)),
                record.get(BATCH_SEARCH.FUZZINESS),
                phraseMatches,
                record.get(BATCH_SEARCH.ERROR_MESSAGE),
                record.get(BATCH_SEARCH.ERROR_QUERY));
    }

    private BatchSearch createBatchSearchWithoutQueries(final Record record) {
        Integer nb_queries = record.get("nb_queries", Integer.class);
        String projects = (String) record.get("projects");
        boolean phraseMatches= record.get(BATCH_SEARCH.PHRASE_MATCHES) != 0;
        return new BatchSearch(record.get(BATCH_SEARCH.UUID).trim(),
                getProjects(projects),
                record.getValue(BATCH_SEARCH.NAME),
                record.getValue(BATCH_SEARCH.DESCRIPTION),
                nb_queries,
                Date.from(record.get(BATCH_SEARCH.BATCH_DATE).toInstant()),
                State.valueOf(record.get(BATCH_SEARCH.STATE)),
                new User(record.get(BATCH_SEARCH.USER_ID)),
                record.get(BATCH_SEARCH.BATCH_RESULTS),
                record.get(BATCH_SEARCH.PUBLISHED) > 0,
                getListFromStringOrNull(record.get(BATCH_SEARCH.FILE_TYPES)),
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
        Object nbQueries = record.getValue("nbQueries");
        String projects = (String) record.get("projects");
        org.icij.datashare.db.tables.records.BatchSearchRecord batchSearch = record.into(BATCH_SEARCH);
        return new BatchSearchRecord(batchSearch.getUuid(),
                getProjects(projects),
                batchSearch.getName(),
                batchSearch.getDescription(),
                (int) nbQueries,
                Date.from(batchSearch.getBatchDate().toInstant()),
                State.valueOf(batchSearch.getState()),
                new User(batchSearch.getUserId()),
                batchSearch.getBatchResults(),
                batchSearch.getPublished() > 0,
                batchSearch.getErrorMessage(),
                batchSearch.getErrorQuery());
    }

    private static List<Project> getProjects(String prj) {
        return prj == null || prj.isEmpty() ? null : stream(prj.split(LIST_SEPARATOR)).sorted().map(Project::project).collect(toList());
    }

    private SearchResult createSearchResult(final User actualUser, final Record record) {
        String owner = record.get(BATCH_SEARCH.USER_ID);
        String prj = record.get(BATCH_SEARCH_RESULT.PRJ_ID);
        boolean published = record.get(BATCH_SEARCH.PUBLISHED)>0;
        if (!actualUser.id.equals(owner) && !published) {
            throw new UnauthorizedUserException(record.get(BATCH_SEARCH.UUID), owner, actualUser.id);
        }
        Timestamp creationDate = record.get(BATCH_SEARCH_RESULT.CREATION_DATE);
        return new SearchResult(record.get(BATCH_SEARCH_RESULT.QUERY),
                prj == null || prj.isEmpty() ? null : project(prj),
                record.get(BATCH_SEARCH_RESULT.DOC_ID),
                record.getValue(BATCH_SEARCH_RESULT.ROOT_ID),
                Paths.get(record.getValue(BATCH_SEARCH_RESULT.DOC_PATH)),
                creationDate == null ? null: new Date(creationDate.getTime()),
                record.getValue(BATCH_SEARCH_RESULT.CONTENT_TYPE),
                record.getValue(BATCH_SEARCH_RESULT.CONTENT_LENGTH),
                record.get(BATCH_SEARCH_RESULT.DOC_NB));
    }

    private static void addFilterToSelectCondition(WebQuery webQuery, SelectConditionStep<? extends Record> query) {
        if(!webQuery.query.equals("") && !webQuery.query.equals("*")){
            String searchQuery = String.format("%s%s%s","%", webQuery.query,"%");
            if(webQuery.field.equals("all")) {
                query.and(BATCH_SEARCH.NAME.like(searchQuery).or(BATCH_SEARCH.DESCRIPTION.like(searchQuery)).or(BATCH_SEARCH.USER_ID.like(searchQuery)));
            }
            else {
                stream(BATCH_SEARCH.fields()).filter(field -> field.getName().equals(webQuery.field)).forEach(field -> query.and(field.like(searchQuery)));
            }
        }
        if (webQuery.hasFilteredDates()) {
            query.and(BATCH_SEARCH.BATCH_DATE.between(new Timestamp(Long.parseLong(webQuery.batchDate.get(0))), new Timestamp(Long.parseLong(webQuery.batchDate.get(1)))));
        }
        if (webQuery.hasFilteredStates()) {
            query.and(BATCH_SEARCH.STATE.in(webQuery.state));
        }
        if (webQuery.hasFilteredPublishStates()) {
            query.and(BATCH_SEARCH.PUBLISHED.eq(Integer.parseInt(webQuery.publishState)));
        }
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
