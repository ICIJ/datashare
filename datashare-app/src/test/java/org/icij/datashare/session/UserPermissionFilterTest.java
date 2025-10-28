package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.UserPermission;
import org.icij.datashare.user.UserPermissionRepository;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class UserPermissionFilterTest {
    private UserPermissionRepository userPermissionRepository;
    private UserPermissionFilter filter;
    private Context context;
    private PayloadSupplier next;
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;

    @Before
    public void setUp() {
        userPermissionRepository = mock(UserPermissionRepository.class);
        filter = new UserPermissionFilter(userPermissionRepository);
        context = mock(Context.class);
        next = mock(PayloadSupplier.class);
        adminUser = mock(DatashareUser.class);
        nonAdminUser = mock(DatashareUser.class);
    }

    @Test
    public void testAdminAccessAllowed() throws Exception {
        when(context.currentUser()).thenReturn(adminUser);
        UserPermission adminPermission = mock(UserPermission.class);
        when(adminPermission.admin()).thenReturn(true);
        when(userPermissionRepository.get(adminUser, "test")).thenReturn(adminPermission);
        when(next.get()).thenReturn(mock(Payload.class));
        assertEquals(next.get(), filter.apply("/api/esPost", context, next));
    }

    @Test(expected = ForbiddenException.class)
    public void testNonAdminAccessDenied() throws Exception {
        when(context.currentUser()).thenReturn(nonAdminUser);
        UserPermission nonAdminPermission = mock(UserPermission.class);
        when(nonAdminPermission.admin()).thenReturn(false);
        when(userPermissionRepository.get(nonAdminUser, "test")).thenReturn(nonAdminPermission);
        filter.apply("/api/esPost", context, next);
    }

    @Test(expected = UnauthorizedException.class)
    public void testNoUserAccessDenied() throws Exception {
        when(context.currentUser()).thenReturn(null);
        filter.apply("/api/esPost", context, next);
    }
}
