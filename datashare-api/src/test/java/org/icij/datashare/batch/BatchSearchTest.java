package org.icij.datashare.batch;

import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.Project.project;

public class BatchSearchTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_create_batch_search_with_no_queries_throws_exception() {
        new BatchSearch(singletonList(project("prj")), "name", "desc", new LinkedHashSet<>(), null, User.local());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_create_batch_search_with_null_queries_throws_exception() {
        new BatchSearch(singletonList(project("prj")), "name", "desc", null, null, User.local());
    }

    @Test
    public void test_has_query_template_true() {
        String query = "{\"query\":3}";
        assertThat(new BatchSearch(singletonList(project("prj")), "name", "desc",
                asSet("q1", "q2"), "/?q=&from=0&size=25&sort=relevance&indices=test&field=all", User.local(), true,
                singletonList("application/json"), "{\"query\":3}", asList("/path/to/docs", "/path/to/pdfs"),
                3,true).hasQueryTemplate()).isTrue();
    }
    @Test
    public void test_has_query_template_false() {
        assertThat(new BatchSearch(singletonList(project("prj")), "name", "desc",
                asSet("q1", "q2"), null, User.local(), true, singletonList("application/json"), null,
                asList("/path/to/docs", "/path/to/pdfs"), 3,true).hasQueryTemplate()).isFalse();
    }

    @Test
    public void test_copy_constructor() {
        DatashareTime.setMockTime(true);
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        BatchSearch batchSearch = new BatchSearch("uuid", singletonList(project("prj")), "name", "desc", new LinkedHashMap<String, Integer>() {{ put("query", 3);}}, 0,
                new Date(), BatchSearchRecord.State.FAILURE, uri, User.local(), 123, true,
                singletonList("application/pdf"), "{\"test\": 42}",singletonList("path"), 2, true,
                "error message", "error query");

        BatchSearch copy = new BatchSearch(batchSearch);

        assertThat(copy.uuid).isNotEqualTo(batchSearch.uuid);
        assertThat(copy.state).isEqualTo(BatchSearchRecord.State.QUEUED);
        assertThat(copy.nbResults).isEqualTo(0);
        assertThat(copy.errorMessage).isNull();
        assertThat(copy.errorQuery).isNull();
        assertThat(copy.date).isEqualTo(DatashareTime.getInstance().now());

        assertThat(copy.projects).isEqualTo(batchSearch.projects);
        assertThat(copy.uri).isEqualTo(batchSearch.uri);
        assertThat(copy.user).isEqualTo(batchSearch.user);
        assertThat(copy.name).isEqualTo(batchSearch.name);
        assertThat(copy.description).isEqualTo(batchSearch.description);
        assertThat(copy.fileTypes).isEqualTo(batchSearch.fileTypes);
        assertThat(copy.queryTemplate).isEqualTo(batchSearch.queryTemplate);
        assertThat(copy.fuzziness).isEqualTo(batchSearch.fuzziness);
        assertThat(copy.paths).isEqualTo(batchSearch.paths);
        assertThat(copy.published).isEqualTo(batchSearch.published);
        assertThat(copy.queries).isEqualTo(batchSearch.queries);
        assertThat(copy.nbQueriesWithoutResults).isEqualTo(batchSearch.queries.size());
    }

    @Test
    public void test_copy_constructor_with_overridden_params() {
        DatashareTime.setMockTime(true);
        BatchSearch batchSearch = new BatchSearch("uuid", asList(project("prj1"), project("prj2")), "name", "desc", new LinkedHashMap<String, Integer>() {{ put("query", 3);}},
                new Date(), BatchSearchRecord.State.FAILURE, "/?q=&from=0&size=25&sort=relevance&indices=test&field=all", User.local(), 123, true,
                singletonList("application/pdf"), null,singletonList("path"), 2, true,
                "error message", "error query");

        BatchSearch copy = new BatchSearch(batchSearch, new HashMap<String, String>() {{
            put("name", "new name");
            put("description", "new description");
        }});

        assertThat(copy.name).isEqualTo("new name");
        assertThat(copy.description).isEqualTo("new description");
    }
}
