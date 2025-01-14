package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.common.settings.Settings;
import org.icij.datashare.test.LogbackCapturingRule;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.event.Level;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;

public class EsEmbeddedServerTest {
    @Rule public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

    @Test
    public void launch_with_bad_codec() {
        try  {
            new EsEmbeddedServer("name", "home/path", "data/path", "9876") {
                @Override
                EsEmbeddedServer.PluginConfigurableNode createNode(Settings settings) {
                    throw new IllegalArgumentException("Could not load codec");
                }
            };
            fail("should send IllegalArgument");
        } catch (IllegalArgumentException iae) {
            assertThat(logbackCapturingRule.logs(Level.ERROR)).contains("Your index version on disk (data/path) doesn't seem to have the same version as the embedded Elasticsearch engine (7.17.9). Please migrate it with snapshots, or remove it then restart datashare.");
        }
    }

    @Test
    public void launch_with_other_illegal_argument() {
        try {
            new EsEmbeddedServer("name", "home/path", "data/path", "9876") {
                @Override
                EsEmbeddedServer.PluginConfigurableNode createNode(Settings settings) {
                    throw new IllegalArgumentException();
                }
            };
            fail("should send IllegalArgument");
        } catch (IllegalArgumentException iae) {
            assertThat(logbackCapturingRule.logs()).isEmpty();
        }
    }

}
