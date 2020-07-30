package org.icij.datashare.batch;

import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.Test;
import java.util.LinkedHashSet;

public class BatchSearchTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_create_batch_search_with_no_queries_throws_exception() {
        new BatchSearch(Project.project("prj"), "name", "desc", new LinkedHashSet<>(), User.local());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_create_batch_search_with_null_queries_throws_exception() {
        new BatchSearch(Project.project("prj"), "name", "desc", null, User.local());
    }
}
