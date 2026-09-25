package org.icij.datashare.tasks;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tabular.StatementBuilder;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class StructuredEntityExtractionResultTest {
    @Test
    public void test_round_trips_through_json_with_its_skip_counts() throws Exception {
        StructuredEntityExtractionResult result = new StructuredEntityExtractionResult(
                3L, 0, 5, 2, Map.of(StatementBuilder.Skip.ENTITY_UNIDENTIFIED, 1L));

        String json = JsonObjectMapper.getMapper().writeValueAsString(result);

        assertThat(JsonObjectMapper.readValue(json, StructuredEntityExtractionResult.class)).isEqualTo(result);
    }

    @Test
    public void test_is_a_registered_task_result_subtype() {
        assertThat(java.util.Arrays.stream(TaskResultSubtypes.values())
                                   .anyMatch(subtype -> subtype.getType() == StructuredEntityExtractionResult.class))
                .isTrue();
    }
}
