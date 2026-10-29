package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class StageActivityOptionsTest {

    @Test
    public void test_non_positive_timeout_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new StageActivityOptions(Duration.ZERO, 0));
    }

    @Test
    public void test_negative_max_attempts_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                     () -> new StageActivityOptions(Duration.ofMinutes(1), -1));
    }

    @Test
    public void test_json_round_trip() throws JsonProcessingException {
        StageActivityOptions options = new StageActivityOptions(Duration.ofHours(6), 3);

        String json = JsonObjectMapper.getMapper().writeValueAsString(options);

        assertThat(JsonObjectMapper.getMapper().readValue(json, StageActivityOptions.class)).isEqualTo(options);
    }
}
