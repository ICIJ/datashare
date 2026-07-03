package org.icij.datashare.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class PdfBoxNoiseFilterTest {

    private final PdfBoxNoiseFilter filter = new PdfBoxNoiseFilter();
    private final LoggerContext context = new LoggerContext();

    private LoggingEvent event(final String loggerName, final Level level, final String message) {
        final Logger logger = context.getLogger(loggerName);
        return new LoggingEvent(Logger.class.getName(), logger, level, message, null, null);
    }

    @Test
    public void denies_fontFallbackNoise() {
        assertThat(filter.decide(event("org.apache.pdfbox.pdmodel.font.PDTrueTypeFont", Level.WARN,
                "Using fallback font LiberationSans for Arial")))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    public void denies_xrefRepairNoise() {
        assertThat(filter.decide(event("org.apache.pdfbox.pdfparser.COSParser", Level.WARN,
                "found wrong object number. expected [12] found [13]")))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    public void denies_noUnicodeMappingNoise() {
        assertThat(filter.decide(event("org.apache.fontbox.ttf.CmapSubtable", Level.WARN,
                "No Unicode mapping for glyph 42")))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    public void denies_noCurrentFontNoise() {
        assertThat(filter.decide(event("org.apache.pdfbox.contentstream.PDFStreamEngine", Level.WARN,
                "No current font, will use default")))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    public void keeps_pdfBoxError() {
        assertThat(filter.decide(event("org.apache.pdfbox.pdmodel.font.PDTrueTypeFont", Level.ERROR,
                "Using fallback font LiberationSans for Arial")))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    public void keeps_otherPdfBoxWarning() {
        assertThat(filter.decide(event("org.apache.pdfbox.pdmodel.PDDocument", Level.WARN,
                "This PDF is malformed")))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    public void ignores_nonPdfBoxEvent() {
        assertThat(filter.decide(event("org.icij.datashare.SomethingElse", Level.WARN,
                "Using fallback font LiberationSans for Arial")))
                .isEqualTo(FilterReply.NEUTRAL);
    }
}
