package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
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
        annotation = new MappingPolicyAnnotation(authorizer, mappings);
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
    public void test_unreadable_mapping_is_a_conflict() {
        when(mappings.get("prj", "m1")).thenThrow(new UnreadableExtractionMapping("m1", new IOException("unknown model")));

        assertEquals(409, applyIn(context("cecile", "prj")));
    }

    @Test
    public void test_wildcard_project_is_a_bad_request() {
        assertEquals(400, applyIn(context("cecile", "*")));
    }

    private int apply(String userId, ExtractionMapping stored) {
        when(mappings.get("prj", "m1")).thenReturn(Optional.ofNullable(stored));
        return applyIn(context(userId, "prj"));
    }

    private int applyIn(Context context) {
        Payload result = annotation.apply(mappingPolicy, context, c -> Payload.ok());
        return result.code();
    }

    private static Context context(String userId, String projectId) {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(new DatashareUser(userId));
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
