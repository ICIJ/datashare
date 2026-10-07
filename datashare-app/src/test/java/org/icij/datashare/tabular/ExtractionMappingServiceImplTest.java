package org.icij.datashare.tabular;

import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tasks.StructuredEntityExtractionTask;
import org.icij.datashare.user.User;
import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import static org.fest.assertions.Assertions.assertThat;
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
        assertThrows(DuplicateExtractionMapping.class, () -> service.save(mapping("companies")));
    }

    @Test
    public void test_save_if_identical_accepts_the_stored_mapping_again() throws Exception {
        when(mappings.save(mapping("companies"))).thenReturn(false);
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        service.saveIfIdentical(mapping("companies"));
    }

    @Test
    public void test_save_if_identical_refuses_a_different_mapping_under_the_same_id() throws Exception {
        when(mappings.save(mapping("people"))).thenReturn(false);
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping("companies")));

        DuplicateExtractionMapping duplicate =
                assertThrows(DuplicateExtractionMapping.class, () -> service.saveIfIdentical(mapping("people")));
        assertThat(duplicate.getMessage()).isEqualTo("mapping 'm1' already exists in project 'prj'");
    }

    @Test
    public void test_save_if_identical_refuses_an_id_whose_stored_mapping_no_longer_reads() throws Exception {
        when(mappings.save(mapping("companies"))).thenReturn(false);
        when(mappings.get("prj", "m1")).thenThrow(new UnreadableExtractionMapping("m1", new IOException("gone")));

        // a stored definition that no longer reads cannot be the caller's, so it stays a conflict
        assertThrows(DuplicateExtractionMapping.class, () -> service.saveIfIdentical(mapping("companies")));
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

    private static ExtractionMapping mapping(String name) throws IOException {
        String json = """
                {"id": "m1", "projectId": "prj", "userId": null, "name": "%s", "model": "ftm", "documentId": "docId",
                 "entities": {"c": {"type": "Company", "keys": ["id"], "properties": {"name": {"columns": ["name"]}}}}}
                """.formatted(name);
        return ExtractionMappingReader.read(JsonObjectMapper.readTree(json.getBytes()));
    }
}
