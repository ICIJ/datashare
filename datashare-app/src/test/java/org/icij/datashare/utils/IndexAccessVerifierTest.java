package org.icij.datashare.utils;

import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IndexAccessVerifierTest {

    private IndexAccessVerifier indexAccessVerifier;

    @Test
    public void test_check_single_index() {
        assertThat(IndexAccessVerifier.checkIndices("foo")).isEqualTo("foo");
    }


    @Test
    public void test_check_invalid_single_index() {
        assertThrows(IllegalArgumentException.class, () -> {
            IndexAccessVerifier.checkIndices("foo?");
        });
    }

    @Test
    public void test_check_multiple_indices() {
        assertThat(IndexAccessVerifier.checkIndices("bar,foo")).isEqualTo("bar,foo");
    }

    @Test
    public void test_check_invalid_multiple_indices() {
        assertThrows(IllegalArgumentException.class, () -> {
            IndexAccessVerifier.checkIndices("bar,foo!");
        });
    }

    @Test
    public void test_check_namespaced_index() {
        assertThat(IndexAccessVerifier.checkIndices("foo.entities")).isEqualTo("foo.entities");
        assertThat(IndexAccessVerifier.checkIndices("bar,foo.entities")).isEqualTo("bar,foo.entities");
    }

    @Test
    public void test_check_invalid_dotted_index() {
        // a leading dot would reach the elasticsearch system indices (.kibana, .security)
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndices(".kibana"));
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndices("foo."));
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndices("foo..entities"));
        // find() against a ^...$ pattern lets a trailing line terminator slip through; matches() does not
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndices("foo\n"));
    }

    @Test
    public void test_check_invalid_underscore_leading_index() {
        // "_all" and its siblings are elasticsearch selectors, not index names: refusing them here
        // means a project row named "_all" (creatable before the name guards) cannot grant the
        // whole cluster to whoever holds it
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndices("_all"));
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndices("foo,_all"));
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndices("_foo.entities"));
        assertThat(IndexAccessVerifier.checkIndices("my_project")).isEqualTo("my_project");
    }

    @Test
    public void test_check_path_grants_entities_index_of_granted_project() {
        // POST, not GET: proves isSearchPath is what authorizes this, not the isMethodGet short-circuit
        assertThat(IndexAccessVerifier.checkPath("foo.entities/_search", contextFor("POST", "foo"))).isEqualTo("foo.entities/_search");
    }

    @Test
    public void test_check_path_refuses_entities_index_of_other_project() {
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("bar.entities/_search", contextFor("GET", "foo")));
    }

    @Test
    public void test_check_path_refuses_unknown_suffix_and_prefix_match() {
        Context context = contextFor("GET", "foo");
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("foo.unknown/_search", context));
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("foobar/_search", context));
        // "myproject-other.entities" strips to "myproject-other", a different project; a prefix match would wrongly grant this
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("myproject-other.entities/_search", contextFor("GET", "myproject")));
        // a mixed list is refused as soon as one index isn't granted, even alongside one that is
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("foo,bar.entities/_search", contextFor("GET", "foo")));
    }

    @Test
    public void test_check_path_refuses_write_on_granted_index() {
        // a grant only ever authorizes GET, _search, _count or _async_search; a write path is refused regardless
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("foo.entities/_bulk", contextFor("POST", "foo")));
    }

    private static Context contextFor(String method, String... grantedProjects) {
        Context context = mock(Context.class);
        Query query = mock(Query.class);
        when(context.currentUser()).thenReturn(new DatashareUser(User.localUser("cecile", grantedProjects)));
        when(context.method()).thenReturn(method);
        when(context.query()).thenReturn(query);
        when(query.keyValues()).thenReturn(new HashMap<>());
        return context;
    }

    @Test
    public void test_is_async_search_submit() {
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("my-index/_async_search")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("a,b/_async_search")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("my-index/_search")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("_async_search/some-id")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("_async_search")).isFalse();
    }

    @Test
    public void test_is_async_search_status_path() {
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("_async_search/some-id")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("_async_search/a/b==")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("my-index/_async_search")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("my-index/_search")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("_async_search")).isFalse();
    }

    @Test
    public void test_async_search_id_reconstructs_full_id() {
        assertThat(IndexAccessVerifier.asyncSearchId("_async_search/abc==")).isEqualTo("abc==");
        // ES ids may contain slashes; the id is everything after "_async_search/"
        assertThat(IndexAccessVerifier.asyncSearchId("_async_search/ab/cd==")).isEqualTo("ab/cd==");
    }
}
