package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class UserDataFilterTest {
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private UserDataFilter userDataFilter = new UserDataFilter();

    @Test
    public void test_matches() {
        assertThat(userDataFilter.matches("/data/foo/bar", null)).isTrue();
        assertThat(userDataFilter.matches("/data/", null)).isTrue();
        assertThat(userDataFilter.matches("/data", null)).isFalse();
        assertThat(userDataFilter.matches("/other", null)).isFalse();
    }

    @Test
    public void test_returns_next_when_user_in_context_is_in_url() throws Exception {
        doReturn(new OAuth2User("user_id")).when(context).currentUser();
        Payload payload = userDataFilter.apply("/data/user_id/foo/bar", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_returns_401_when_user_in_context_is_not_in_url() throws Exception {
        doReturn(new OAuth2User("user_id")).when(context).currentUser();
        Payload payload = userDataFilter.apply("/data/user2_id/foo/bar", context, nextFilter);
        assertThat(payload.code()).isEqualTo(401);
    }
}