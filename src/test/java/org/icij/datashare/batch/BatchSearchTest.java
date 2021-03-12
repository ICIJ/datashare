package org.icij.datashare.batch;

import org.icij.datashare.text.Project;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

public class BatchSearchTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_create_batch_search_with_no_queries_throws_exception() {
        new BatchSearch(Project.project("prj"), "name", "desc", new LinkedHashSet<>(), User.local());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_create_batch_search_with_null_queries_throws_exception() {
        new BatchSearch(Project.project("prj"), "name", "desc", null, User.local());
    }

    @Test
    public void test_copy_constructor() {
        DatashareTime.setMockTime(true);
        BatchSearch batchSearch = new BatchSearch("uuid", Project.project("prj"), "name", "desc", new LinkedHashMap<String, Integer>() {{ put("query", 3);}},
                new Date(), BatchSearchRecord.State.FAILURE, User.local(), 123, true,
                asList("application/pdf"), asList("path"), 2, true,
                "error messsage", "error query");

        BatchSearch copy = new BatchSearch(batchSearch);

        assertThat(copy.uuid).isNotEqualTo(batchSearch.uuid);
        assertThat(copy.state).isEqualTo(BatchSearchRecord.State.QUEUED);
        assertThat(copy.nbResults).isEqualTo(0);
        assertThat(copy.errorMessage).isNull();
        assertThat(copy.errorQuery).isNull();
        assertThat(copy.date).isEqualTo(DatashareTime.getInstance().now());

        assertThat(copy.project).isEqualTo(batchSearch.project);
        assertThat(copy.user).isEqualTo(batchSearch.user);
        assertThat(copy.name).isEqualTo(batchSearch.name);
        assertThat(copy.description).isEqualTo(batchSearch.description);
        assertThat(copy.fileTypes).isEqualTo(batchSearch.fileTypes);
        assertThat(copy.fuzziness).isEqualTo(batchSearch.fuzziness);
        assertThat(copy.paths).isEqualTo(batchSearch.paths);
        assertThat(copy.published).isEqualTo(batchSearch.published);
        assertThat(copy.queries).isEqualTo(batchSearch.queries);
    }

    @Test
    public void test_copy_constructor_with_overridden_params() {
        DatashareTime.setMockTime(true);
        BatchSearch batchSearch = new BatchSearch("uuid", Project.project("prj"), "name", "desc", new LinkedHashMap<String, Integer>() {{ put("query", 3);}},
                new Date(), BatchSearchRecord.State.FAILURE, User.local(), 123, true,
                asList("application/pdf"), asList("path"), 2, true,
                "error message", "error query");

        BatchSearch copy = new BatchSearch(batchSearch, new HashMap<String, String>() {{
            put("name", "new name");
            put("description", "new description");
            put("project", "new prj");
        }});

        assertThat(copy.name).isEqualTo("new name");
        assertThat(copy.description).isEqualTo("new description");
        assertThat(copy.project.name).isEqualTo("new prj");
    }
}
