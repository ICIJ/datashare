package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.elasticsearch.AbstractElasticSearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.extract.extractor.Extractor;
import org.junit.Test;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Function;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class IndexStageTest {
    private final PropertiesProvider properties = new PropertiesProvider();
    private final Extractor extractor = mock(Extractor.class);

    @Test
    public void test_a_failing_index_creation_is_unchecked_and_closes_the_extractor() throws Exception {
        AbstractElasticSearchSpewer spewer = mock(AbstractElasticSearchSpewer.class);
        IOException failure = new IOException("elasticsearch is down");
        doThrow(failure).when(spewer).createIndexIfNotExists();

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> stageWith(recorder -> spewer));

        assertThat(thrown.getCause()).isSameAs(failure);
        verify(extractor).close();
    }

    @Test
    public void test_a_failing_spewer_factory_is_rethrown_and_closes_the_extractor() {
        IllegalStateException failure = new IllegalStateException("bad artifacts config");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> stageWith(recorder -> {
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        verify(extractor).close();
    }

    private IndexStage stageWith(Function<ManifestRecorder, ? extends AbstractElasticSearchSpewer> spewerFactory) {
        return new IndexStage(IndexOptions.of(properties), properties.toRecord(ArtifactOptions.class), List::of, false,
                              "task-id", (documentFactory, options) -> extractor, spewerFactory);
    }
}
