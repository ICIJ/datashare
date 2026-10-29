package org.icij.datashare.asynctasks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.icij.datashare.Stage;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class StageRegistryTest {
    @Test
    public void test_activity_options_are_correctly_overridden() {
        StageActivityOptions overridden = new StageActivityOptions(Duration.ofHours(6), 3);
        StageRegistry registry = new StageRegistry().register(new StubProcessor(Stage.SCAN, null))
                                                    .register(new StubProcessor(Stage.INDEX, overridden));

        Map<Stage, StageActivityOptions> options = registry.activityOptions(List.of(Stage.SCAN, Stage.INDEX));

        assertThat(options.get(Stage.SCAN)).isEqualTo(StageActivityOptions.DEFAULT);
        assertThat(options.get(Stage.INDEX)).isEqualTo(overridden);
    }

    @Test
    public void test_activity_options_of_an_unregistered_stage_fail() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                                                  () -> new StageRegistry().activityOptions(List.of(Stage.NLP)));

        assertThat(e.getMessage()).contains("NLP");
    }

    @Test
    public void test_a_processor_declaring_no_activity_options_is_rejected_before_any_workflow_starts() {
        StageRegistry registry = new StageRegistry().register(new StageProcessor<Void, Void>() {
            public Stage stage() {return Stage.INDEX;}

            public Class<Void> itemType() {return Void.class;}

            public StageResult process(List<Void> batch, StageContext<Void> ctx) {return StageResult.EMPTY;}

            public StageActivityOptions activityOptions() {return null;}
        });

        NullPointerException e = assertThrows(NullPointerException.class,
                                              () -> registry.activityOptions(List.of(Stage.INDEX)));

        assertThat(e.getMessage()).contains("INDEX");
    }

    private record StubProcessor(Stage stage, StageActivityOptions overridden) implements StageProcessor<Void, Void> {
        @Override
        public Class<Void> itemType() {return Void.class;}

        @Override
        public StageResult process(List<Void> batch, StageContext<Void> ctx) {return StageResult.EMPTY;}

        @Override
        public StageActivityOptions activityOptions() {
            return overridden == null ? StageProcessor.super.activityOptions() : overridden;
        }
    }
}
