package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserRepository;
import org.junit.Before;
import org.junit.Test;

import java.net.URISyntaxException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UserPolicyAnnotationTest {
    private UserRepository userRepository;
    private UserPolicyAnnotation filter;
    private Context context;
    private PayloadSupplier next;
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;

    @Before
    public void setUp() throws URISyntaxException {
        userRepository = mock(UserRepository.class);
        filter = new UserPolicyAnnotation(userRepository);
        context = mock(Context.class);
        next = mock(PayloadSupplier.class);
        adminUser = mock(DatashareUser.class);
        nonAdminUser = mock(DatashareUser.class);
    }

    @Test
    public void testAdminAccessAllowed() throws Exception {
        when(context.currentUser()).thenReturn(adminUser);
        when(context.method()).thenReturn("PUT");
        when(context.uri()).thenReturn("/api/test-datashare");
        UserPolicy adminPermission = mock(UserPolicy.class);
        when(adminPermission.admin()).thenReturn(true);
        when(userRepository.get(adminUser, "test-datashare")).thenReturn(adminPermission);
        when(next.get()).thenReturn(mock(Payload.class));
      //  assertEquals(next.get(), filter.apply());
    }

    @Test(expected = ForbiddenException.class)
    public void testNonAdminAccessDenied() throws Exception {
        when(context.currentUser()).thenReturn(nonAdminUser);
        UserPolicy nonAdminPermission = mock(UserPolicy.class);
        when(nonAdminPermission.admin()).thenReturn(false);
        when(userRepository.get(nonAdminUser, "test")).thenReturn(nonAdminPermission);
       // filter.apply("/api/esPost", context, next);
    }

    @Test(expected = UnauthorizedException.class)
    public void testNoUserAccessDenied() throws Exception {
        when(context.currentUser()).thenReturn(null);
       // filter.apply("/api/esPost", context, next);
    }
}
