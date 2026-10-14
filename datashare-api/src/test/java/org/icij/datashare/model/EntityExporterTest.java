package org.icij.datashare.model;

import org.icij.datashare.model.ftm.FtmJsonExporter;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class EntityExporterTest {
    @Test
    public void test_ftm_has_an_exporter() {
        assertThat(EntityExporter.forModel("ftm")).isInstanceOf(FtmJsonExporter.class);
    }

    @Test
    public void test_a_model_with_no_exporter_is_refused_naming_the_known_ones() {
        UnknownTargetModel refused = assertThrows(UnknownTargetModel.class, () -> EntityExporter.forModel("wikidata"));

        assertThat(refused.getMessage()).contains("wikidata").contains("[ftm]");
    }

    @Test
    public void test_a_missing_model_is_refused() {
        assertThrows(UnknownTargetModel.class, () -> EntityExporter.forModel(null));
    }
}
