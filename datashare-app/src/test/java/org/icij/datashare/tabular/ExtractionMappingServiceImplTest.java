package org.icij.datashare.tabular;

import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.tasks.StructuredEntityExtractionTask;
import org.icij.datashare.user.User;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A mapping id is saved once per project, only the identical mapping may be saved again, and only a stored one runs. */
public class ExtractionMappingServiceImplTest {
    private final ExtractionMappingRepository mappings = mock(ExtractionMappingRepository.class);
    private final TaskManager taskManager = mock(TaskManager.class);
    private final ExtractionMappingService service = new ExtractionMappingServiceImpl(mappings, taskManager);

    @Test
    public void test_get_lets_anyone_read_when_roles_are_not_enforced() throws Exception {
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        assertThat(service.get("prj", "m1", User.localUser("anyone")).isPresent()).isTrue();
    }

    @Test
    public void test_get_lets_a_cli_run_read_when_roles_are_enforced() throws Exception {
        ExtractionMappingService enforced =
                new ExtractionMappingServiceImpl(mappings, new Authorizer(mock(CasbinRuleAdapter.class)), taskManager);
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        assertThat(enforced.get("prj", "m1", User.nullUser()).isPresent()).isTrue();
    }

    @Test
    public void test_save_stores_a_new_mapping() throws Exception {
        ExtractionMapping mapping = mapping("companies");
        when(mappings.save(mapping)).thenReturn(true);

        service.save(mapping);

        verify(mappings).save(mapping);
    }

    @Test
    public void test_save_refuses_an_id_the_project_already_holds() throws Exception {
        when(mappings.save(mapping("companies"))).thenReturn(false);
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        // an immutable mapping cannot be overwritten, even with an identical body
        DuplicateExtractionMapping duplicate =
                assertThrows(DuplicateExtractionMapping.class, () -> service.save(mapping("companies")));
        assertThat(duplicate.reason).isEqualTo(DuplicateExtractionMapping.Reason.ID_TAKEN);
        assertThat(duplicate.getMessage()).isEqualTo(
                "mapping 'm1' already exists in project 'prj': a mapping is immutable, so save this one under a new id");
    }

    @Test
    public void test_save_if_identical_accepts_the_stored_mapping_again() throws Exception {
        when(mappings.save(mapping("companies"))).thenReturn(false);
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        assertThat(service.saveIfIdentical(mapping("companies"))).isFalse();
    }

    @Test
    public void test_save_if_identical_reports_a_new_mapping() throws Exception {
        when(mappings.save(mapping("companies"))).thenReturn(true);

        assertThat(service.saveIfIdentical(mapping("companies"))).isTrue();
    }

    @Test
    public void test_save_if_identical_refuses_a_different_mapping_under_the_same_id() throws Exception {
        when(mappings.save(mapping("people"))).thenReturn(false);
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        DuplicateExtractionMapping duplicate =
                assertThrows(DuplicateExtractionMapping.class, () -> service.saveIfIdentical(mapping("people")));
        assertThat(duplicate.reason).isEqualTo(DuplicateExtractionMapping.Reason.DIFFERENT_DEFINITION);
        assertThat(duplicate.getMessage()).isEqualTo(
                "mapping 'm1' already exists in project 'prj' with a different definition: save the change under a new id");
    }

    @Test
    public void test_save_if_identical_refuses_an_id_whose_stored_mapping_no_longer_reads() throws Exception {
        when(mappings.save(mapping("companies"))).thenReturn(false);
        when(mappings.get("prj", "m1")).thenThrow(new UnreadableExtractionMapping("m1", null, new IOException("gone")));

        // a stored definition that no longer reads cannot be the caller's, so it stays a conflict
        DuplicateExtractionMapping duplicate =
                assertThrows(DuplicateExtractionMapping.class, () -> service.saveIfIdentical(mapping("companies")));
        assertThat(duplicate.reason).isEqualTo(DuplicateExtractionMapping.Reason.UNREADABLE_DEFINITION);
        assertThat(duplicate.getMessage()).isEqualTo(
                "mapping 'm1' already exists in project 'prj' but its stored definition no longer reads");
    }

    @Test
    public void test_run_starts_the_extraction_task_on_the_stored_mapping() throws Exception {
        User user = User.localUser("john");
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));
        when(taskManager.startTask(StructuredEntityExtractionTask.class, user,
                                   Map.of("defaultProject", "prj", "mappingId", "m1"))).thenReturn("task-1");

        assertThat(service.run("prj", "m1", user)).isEqualTo("task-1");
    }

    @Test
    public void test_run_refuses_a_mapping_the_project_does_not_hold() throws Exception {
        when(mappings.get("prj", "m1")).thenReturn(Optional.empty());

        assertThrows(UnknownExtractionMapping.class, () -> service.run("prj", "m1", User.localUser("john")));
        verify(taskManager, never()).startTask(any(Class.class), any(User.class), any(Map.class));
    }

    @Test
    public void test_run_forbids_a_member_someone_else_mapping() throws Exception {
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mappingOwnedBy("someone")));

        assertThrows(ForbiddenExtractionMapping.class, () -> enforced().run("prj", "m1", User.localUser("john")));
        verify(taskManager, never()).startTask(any(Class.class), any(User.class), any(Map.class));
    }

    @Test
    public void test_get_lets_an_owner_with_the_member_role_through() throws Exception {
        assertThat(getAs("john", mappingOwnedBy("john")).isPresent()).isTrue();
    }

    @Test
    public void test_get_forbids_an_owner_without_role_on_the_project() throws Exception {
        assertThrows(ForbiddenExtractionMapping.class, () -> getAs("jane", mappingOwnedBy("jane")));
    }

    @Test
    public void test_get_forbids_an_owner_with_the_visitor_role() throws Exception {
        assertThrows(ForbiddenExtractionMapping.class, () -> getAs("vera", mappingOwnedBy("vera")));
    }

    @Test
    public void test_get_forbids_a_member_someone_else_mapping() throws Exception {
        assertThrows(ForbiddenExtractionMapping.class, () -> getAs("john", mappingOwnedBy("someone")));
    }

    @Test
    public void test_get_lets_an_admin_read_someone_else_mapping() throws Exception {
        assertThat(getAs("cecile", mappingOwnedBy("john")).isPresent()).isTrue();
    }

    @Test
    public void test_get_lets_an_admin_read_a_cli_mapping() throws Exception {
        assertThat(getAs("cecile", mappingOwnedBy(null)).isPresent()).isTrue();
    }

    @Test
    public void test_get_forbids_a_member_a_cli_mapping() throws Exception {
        assertThrows(ForbiddenExtractionMapping.class, () -> getAs("john", mappingOwnedBy(null)));
    }

    @Test
    public void test_get_tells_an_admin_the_mapping_is_unknown() throws Exception {
        assertThat(getAs("cecile", null).isPresent()).isFalse();
    }

    @Test
    public void test_get_forbids_a_user_without_role_before_looking_the_mapping_up() {
        // a known id answering otherwise than an unknown one would let anyone list a project's mapping ids
        assertThrows(ForbiddenExtractionMapping.class, () -> getAs("jane", null));
        verify(mappings, never()).get(any(), any());
    }

    @Test
    public void test_get_forbids_a_member_an_unknown_mapping_like_someone_else_mapping() throws Exception {
        assertThrows(ForbiddenExtractionMapping.class, () -> getAs("john", null));
    }

    @Test
    public void test_get_reports_an_unreadable_mapping_to_an_admin() {
        assertThrows(UnreadableExtractionMapping.class, () -> getUnreadableAs("cecile", "someone"));
    }

    @Test
    public void test_get_reports_an_unreadable_mapping_to_its_owner() {
        assertThrows(UnreadableExtractionMapping.class, () -> getUnreadableAs("john", "john"));
    }

    @Test
    public void test_get_forbids_a_member_someone_else_unreadable_mapping() {
        assertThrows(ForbiddenExtractionMapping.class, () -> getUnreadableAs("john", "someone"));
    }

    private Optional<ExtractionMapping> getAs(String userId, ExtractionMapping stored) throws IOException {
        when(mappings.get("prj", "m1")).thenReturn(Optional.ofNullable(stored));
        return enforced().get("prj", "m1", User.localUser(userId));
    }

    private Optional<ExtractionMapping> getUnreadableAs(String userId, String ownerId) throws IOException {
        when(mappings.get("prj", "m1")).thenThrow(
                new UnreadableExtractionMapping("m1", ownerId, new IOException("unknown model")));
        return enforced().get("prj", "m1", User.localUser(userId));
    }

    private ExtractionMappingService enforced() throws IOException {
        Authorizer authorizer = new Authorizer(mock(CasbinRuleAdapter.class));
        authorizer.addRoleForUserInProject(User.localUser("cecile"), Role.PROJECT_ADMIN, Domain.DEFAULT, project("prj"));
        authorizer.addRoleForUserInProject(User.localUser("john"), Role.PROJECT_MEMBER, Domain.DEFAULT, project("prj"));
        authorizer.addRoleForUserInProject(User.localUser("vera"), Role.PROJECT_VISITOR, Domain.DEFAULT, project("prj"));
        return new ExtractionMappingServiceImpl(mappings, authorizer, taskManager);
    }

    private static ExtractionMapping mappingOwnedBy(String userId) {
        return new ExtractionMapping("m1", "prj", userId, "companies", "ftm", "docId", null, RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
    }

    private static ExtractionMapping mapping(String name) throws IOException {
        String json = """
                {"id": "m1", "projectId": "prj", "userId": null, "name": "%s", "model": "ftm", "documentId": "docId",
                 "entities": {"c": {"type": "Company", "keys": ["id"], "properties": {"name": {"columns": ["name"]}}}}}
                """.formatted(name);
        return ExtractionMappingReader.read(ExtractionMappingReader.parse(json.getBytes()), "prj", null);
    }
}
