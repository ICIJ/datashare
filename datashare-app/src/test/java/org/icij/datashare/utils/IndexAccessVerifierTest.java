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
    }

    @Test
    public void test_check_path_grants_entities_index_of_granted_project() {
        assertThat(IndexAccessVerifier.checkPath("foo.entities/_search", contextFor("foo"))).isEqualTo("foo.entities/_search");
    }

    @Test
    public void test_check_path_refuses_entities_index_of_other_project() {
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("bar.entities/_search", contextFor("foo")));
    }

    @Test
    public void test_check_path_refuses_unknown_suffix_and_prefix_match() {
        Context context = contextFor("foo");
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("foo.unknown/_search", context));
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("foobar/_search", context));
        // "myproject-other" doesn't end in ".entities" once stripped down to "myproject", it stays its own project
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkPath("myproject-other.entities/_search", contextFor("myproject")));
    }

    private static Context contextFor(String... grantedProjects) {
        Context context = mock(Context.class);
        Query query = mock(Query.class);
        when(context.currentUser()).thenReturn(new DatashareUser(User.localUser("cecile", grantedProjects)));
        when(context.method()).thenReturn("GET");
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
