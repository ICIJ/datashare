package org.icij.datashare.tabular;

import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.user.User;
import org.junit.Test;
import java.io.IOException;
import java.util.Optional;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A mapping id is saved once per project; only the identical mapping may be saved again. */
public class ExtractionMappingServiceImplTest {
    private final ExtractionMappingRepository mappings = mock(ExtractionMappingRepository.class);
    private final ExtractionMappingService service = new ExtractionMappingServiceImpl(mappings);

    @Test
    public void test_get_lets_anyone_read_when_roles_are_not_enforced() throws Exception {
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        assertThat(service.get("prj", "m1", User.localUser("anyone")).isPresent()).isTrue();
    }

    @Test
    public void test_get_lets_a_cli_run_read_when_roles_are_enforced() throws Exception {
        ExtractionMappingService enforced =
                new ExtractionMappingServiceImpl(mappings, new Authorizer(mock(CasbinRuleAdapter.class)));
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

    private static ExtractionMapping mapping(String name) throws IOException {
        String json = """
                {"id": "m1", "projectId": "prj", "userId": null, "name": "%s", "model": "ftm", "documentId": "docId",
                 "entities": {"c": {"type": "Company", "keys": ["id"], "properties": {"name": {"columns": ["name"]}}}}}
                """.formatted(name);
        return ExtractionMappingReader.read(ExtractionMappingReader.parse(json.getBytes()), "prj", null);
    }
}
