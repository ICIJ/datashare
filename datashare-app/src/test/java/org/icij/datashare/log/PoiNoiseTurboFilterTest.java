package org.icij.datashare.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class PoiNoiseTurboFilterTest {
    LoggerContext context = new LoggerContext();
    PoiNoiseTurboFilter filter = new PoiNoiseTurboFilter();

    @Test
    public void test_denies_hwmf_text_metrics_noise_at_info_and_warn() {
        assertThat(decide("org.apache.poi.hwmf.record.HwmfText", Level.INFO, "META_EXTTEXTOUT doesn't contain character tracking info")).isEqualTo(FilterReply.DENY);
        assertThat(decide("org.apache.poi.hwmf.record.HwmfText", Level.WARN, "META_EXTTEXTOUT tracking info doesn't cover all characters")).isEqualTo(FilterReply.DENY);
    }

    @Test
    public void test_denies_hslf_text_paragraph_noise() {
        assertThat(decide("org.apache.poi.hslf.usermodel.HSLFTextParagraph", Level.WARN, "MasterSheet is not available")).isEqualTo(FilterReply.DENY);
        assertThat(decide("org.apache.poi.hslf.usermodel.HSLFTextParagraph", Level.INFO, "bytes nor chars atom doesn't exist. Creating dummy record for later saving.")).isEqualTo(FilterReply.DENY);
    }

    @Test
    public void test_denies_hslf_record_style_runs_noise() {
        assertThat(decide("org.apache.poi.hslf.record.Record", Level.WARN, "Problem reading paragraph style runs: textHandled = 0, text.size+1 = 1")).isEqualTo(FilterReply.DENY);
        assertThat(decide("org.apache.poi.hslf.record.Record", Level.WARN, "Problem reading character style runs: textHandled = 0, text.size+1 = 1")).isEqualTo(FilterReply.DENY);
    }

    @Test
    public void test_keeps_other_messages_of_filtered_loggers() {
        assertThat(decide("org.apache.poi.hslf.record.Record", Level.WARN, "Warning: Skipping record of unknown type")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide("org.apache.poi.hslf.usermodel.HSLFTextParagraph", Level.DEBUG, "some debug diagnostic")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide("org.apache.poi.hwmf.record.HwmfText", Level.WARN, "some other warning")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    public void test_keeps_errors_and_other_loggers() {
        assertThat(decide("org.apache.poi.hslf.record.Record", Level.ERROR, "Problem reading paragraph style runs: textHandled = 0, text.size+1 = 1")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide("org.icij.datashare.tasks.ArtifactTask", Level.INFO, "META_EXTTEXTOUT doesn't contain character tracking info")).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide("org.apache.poi.hslf.record.Record", Level.WARN, null)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    public void test_filter_is_installed_by_prod_logback_configuration() throws Exception {
        LoggerContext prodContext = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(prodContext);
        configurator.doConfigure(getClass().getResource("/logback-prod.xml"));

        assertThat(prodContext.getTurboFilterList().stream().anyMatch(f -> f instanceof PoiNoiseTurboFilter)).isTrue();
    }

    private FilterReply decide(String loggerName, Level level, String message) {
        return filter.decide(null, context.getLogger(loggerName), level, message, null, null);
    }
}
