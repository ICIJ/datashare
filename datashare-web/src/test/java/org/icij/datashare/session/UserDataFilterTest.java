package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.session.HashMapUser.local;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class UserDataFilterTest {
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private UserDataFilter userDataFilter = new UserDataFilter();

    @Test
    public void test_matches() {
        assertThat(userDataFilter.matches("/api/data/foo/bar", null)).isTrue();
        assertThat(userDataFilter.matches("/api/data/", null)).isTrue();
        assertThat(userDataFilter.matches("/api/data", null)).isFalse();
        assertThat(userDataFilter.matches("/api/other", null)).isFalse();
    }

    @Test
    public void test_returns_next_when_user_in_context_is_in_url() throws Exception {
        doReturn(new HashMapUser("user_id")).when(context).currentUser();
        Payload payload = userDataFilter.apply("/api/data/user_id/foo/bar", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_returns_401_when_user_in_context_is_not_in_url() throws Exception {
        doReturn(new HashMapUser("user_id")).when(context).currentUser();
        Payload payload = userDataFilter.apply("/api/data/user2_id/foo/bar", context, nextFilter);
        assertThat(payload.code()).isEqualTo(401);
    }

    @Test
    public void test_returns_401_when_no_user_in_context() throws Exception {
        doReturn(null).when(context).currentUser();
        Payload payload = userDataFilter.apply("/api/data/user2_id/foo/bar", context, nextFilter);
        assertThat(payload.code()).isEqualTo(401);
    }

    @Test
    public void test_returns_next_when_in_local_mode() throws Exception {
        doReturn(local()).when(context).currentUser();
        Payload payload = userDataFilter.apply("/api/data/baz/foo/bar", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }
}