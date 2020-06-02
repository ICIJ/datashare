package org.icij.datashare.text.nlp;

import org.icij.datashare.text.Language;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.text.Language.*;

public class AbstractModelsTest {
    @Before public void setUp() { System.clearProperty(AbstractModels.JVM_PROPERTY_NAME);}

    @Test
    public void test_sync_models_true_by_default() throws Exception {
        ConcreteModelsForTesting models = new ConcreteModelsForTesting(Pipeline.Type.CORENLP, NlpStage.NER);
        assertThat(models.get(WOLOF)).includes(entry("foo", "bar"));

        assertThat(models.hasBeenDownloaded).isTrue();
    }

    @Test
    public void test_sync_models_false() throws Exception {
        AbstractModels.syncModels(false);
        ConcreteModelsForTesting models = new ConcreteModelsForTesting(Pipeline.Type.CORENLP, NlpStage.NER);
        assertThat(models.get(BRETON)).includes(entry("foo", "bar"));

        assertThat(models.hasBeenDownloaded).isFalse();
    }

    @Test
    public void test_sync_models_is_dynamic() throws Exception {
        ConcreteModelsForTesting models = new ConcreteModelsForTesting(Pipeline.Type.CORENLP, NlpStage.NER);
        models.get(WELSH);
        assertThat(models.hasBeenDownloaded).isTrue();

        AbstractModels.syncModels(false);
        models.hasBeenDownloaded = false;
        models.get(ARMENIAN);
        assertThat(models.hasBeenDownloaded).isFalse();
    }

    private static class ConcreteModelsForTesting extends AbstractModels<HashMap> {
        boolean hasBeenDownloaded = false;
        ConcreteModelsForTesting(Pipeline.Type type, NlpStage stage) { super(type, stage);}
        @Override protected HashMap loadModelFile(Language language) { return new HashMap() {{
            put("foo", "bar");
        }};}
        @Override protected String getVersion() { return "1.0";}
        @Override protected void downloadIfNecessary(Language language) { this.hasBeenDownloaded = true;}
    }
}
