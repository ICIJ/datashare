package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BasicAuthAdaptorFilterTest {
    private final Payload next = Payload.ok();
    private final PayloadSupplier nextFilter = () -> next;
    private final Context context = mock(Context.class);
    @Mock
    UsersWritable users;

    @Before
    public void setUp() {
        initMocks(this);
    }

    @Test
    public void test_enroll_is_called_after_successful_auth() throws Exception {
        PostLoginEnroller enroller = mock(PostLoginEnroller.class);
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "alice");
        }});
        when(users.find(eq("alice"), any())).thenReturn(user);
        when(users.find(eq("alice"))).thenReturn(user);
        BasicAuthAdaptorFilter filter = new BasicAuthAdaptorFilter(new PropertiesProvider(), users, enroller);
        when(context.currentUser()).thenReturn(new DatashareUser("alice"));
        when(context.header("Authorization")).thenReturn("Basic YWxpY2U6cGFzcw=="); // alice:pass

        filter.apply("/protected", context, nextFilter);

        verify(enroller).enroll(any(DatashareUser.class));
    }

    @Test
    public void test_enroll_is_not_called_on_signout() throws Exception {
        PostLoginEnroller enroller = mock(PostLoginEnroller.class);
        BasicAuthAdaptorFilter filter = new BasicAuthAdaptorFilter(
                new PropertiesProvider(), mock(UsersWritable.class), enroller);

        filter.apply("/auth/signout", context, nextFilter);

        verify(enroller, never()).enroll(any());
    }
}
