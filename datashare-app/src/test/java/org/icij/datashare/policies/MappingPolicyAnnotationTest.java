package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.ExtractionMappingServiceImpl;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
import org.icij.datashare.web.errors.BadRequestException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static junit.framework.TestCase.assertEquals;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class MappingPolicyAnnotationTest {
    private final MappingPolicy mappingPolicy = new MappingPolicy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return MappingPolicy.class;
        }
    };
    @Mock
    CasbinRuleAdapter adapter;
    @Mock
    ExtractionMappingRepository mappings;
    private AutoCloseable mocks;
    private MappingPolicyAnnotation annotation;

    @Before
    public void setUp() throws IOException {
        mocks = openMocks(this);
        Authorizer authorizer = new Authorizer(adapter);
        authorizer.addRoleForUserInProject(localUser("cecile"), Role.PROJECT_ADMIN, Domain.DEFAULT, project("prj"));
        authorizer.addRoleForUserInProject(localUser("john"), Role.PROJECT_MEMBER, Domain.DEFAULT, project("prj"));
        authorizer.addRoleForUserInProject(localUser("vera"), Role.PROJECT_VISITOR, Domain.DEFAULT, project("prj"));
        annotation = new MappingPolicyAnnotation(new ExtractionMappingServiceImpl(mappings, authorizer, null));
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void test_owner_with_member_role_is_let_through() {
        assertEquals(200, apply("john", mappingOwnedBy("john")));
    }

    @Test
    public void test_owner_without_role_on_the_project_is_forbidden() {
        assertEquals(403, apply("jane", mappingOwnedBy("jane")));
    }

    @Test
    public void test_owner_with_visitor_role_is_forbidden() {
        assertEquals(403, apply("vera", mappingOwnedBy("vera")));
    }

    @Test
    public void test_anonymous_caller_is_unauthorized() {
        assertThrows(UnauthorizedException.class, () -> applyIn(context(null, "prj")));
    }

    @Test
    public void test_member_running_someone_else_mapping_is_forbidden() {
        assertEquals(403, apply("john", mappingOwnedBy("someone")));
    }

    @Test
    public void test_admin_running_someone_else_mapping_is_let_through() {
        assertEquals(200, apply("cecile", mappingOwnedBy("john")));
    }

    @Test
    public void test_admin_running_a_cli_mapping_is_let_through() {
        assertEquals(200, apply("cecile", mappingOwnedBy(null)));
    }

    @Test
    public void test_member_running_a_cli_mapping_is_forbidden() {
        assertEquals(403, apply("john", mappingOwnedBy(null)));
    }

    @Test
    public void test_unknown_mapping_is_not_found() {
        assertEquals(404, apply("cecile", null));
    }

    @Test
    public void test_user_without_role_cannot_tell_an_unknown_mapping_from_a_known_one() {
        // a 404 here and a 403 for an existing id would let anyone list a project's mapping ids
        assertEquals(403, apply("jane", null));
        verify(mappings, never()).get(any(), any());
    }

    @Test
    public void test_member_cannot_tell_an_unknown_mapping_from_someone_else_mapping() {
        assertEquals(403, apply("john", null));
    }

    @Test
    public void test_unreadable_mapping_is_a_conflict_for_an_admin() {
        assertEquals(409, applyUnreadable("cecile", "someone"));
    }

    @Test
    public void test_unreadable_mapping_is_a_conflict_for_its_owner() {
        assertEquals(409, applyUnreadable("john", "john"));
    }

    @Test
    public void test_member_cannot_tell_an_unreadable_mapping_from_someone_else_mapping() {
        assertEquals(403, applyUnreadable("john", "someone"));
    }

    @Test
    public void test_wildcard_project_is_a_bad_request() {
        assertEquals(400, assertThrows(BadRequestException.class, () -> applyIn(context("cecile", "*"))).code());
    }

    private int apply(String userId, ExtractionMapping stored) {
        when(mappings.get("prj", "m1")).thenReturn(Optional.ofNullable(stored));
        return applyIn(context(userId, "prj"));
    }

    private int applyUnreadable(String userId, String ownerId) {
        when(mappings.get("prj", "m1")).thenThrow(
                new UnreadableExtractionMapping("m1", ownerId, new IOException("unknown model")));
        return applyIn(context(userId, "prj"));
    }

    private int applyIn(Context context) {
        Payload result = annotation.apply(mappingPolicy, context, c -> Payload.ok());
        return result.code();
    }

    private static Context context(String userId, String projectId) {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(userId == null ? null : new DatashareUser(userId));
        when(context.pathParam("project")).thenReturn(projectId);
        when(context.pathParam("mappingId")).thenReturn("m1");
        return context;
    }

    private static ExtractionMapping mappingOwnedBy(String userId) {
        return new ExtractionMapping("m1", "prj", userId, "companies", "ftm", "docId", null, RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
    }
}
