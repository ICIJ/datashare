package org.icij.datashare.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Drops PDFBox/FontBox's high-volume, benign warnings (font fallback, xref auto-repair and
 * missing-Unicode-mapping messages) that dominate ~85% of datashare's WARN volume even though text
 * still extracts correctly. Only WARN-and-below events on the org.apache.pdfbox / org.apache.fontbox
 * loggers are considered, so genuine ERRORs and unrelated warnings always pass through. The noise
 * originates inside PDFBox, so message-level filtering is the only way to separate it from real
 * warnings on the same logger. This mirrors extract-cli's filter of the same name, which is absent
 * from datashare's runtime jar because datashare depends on extract-lib rather than extract-cli.
 * Extend NOISE_SUBSTRINGS as further dominant benign messages are measured.
 */
public class PdfBoxNoiseFilter extends Filter<ILoggingEvent> {

    private static final String[] NOISE_SUBSTRINGS = {
            "No current font",
            "Using fallback font",
            "No Unicode mapping",
            "found wrong object number",
    };

    @Override
    public FilterReply decide(final ILoggingEvent event) {
        final String loggerName = event.getLoggerName();
        if (loggerName == null
                || (!loggerName.startsWith("org.apache.pdfbox") && !loggerName.startsWith("org.apache.fontbox"))) {
            return FilterReply.NEUTRAL;
        }
        if (event.getLevel().isGreaterOrEqual(Level.ERROR)) {
            return FilterReply.NEUTRAL;
        }
        final String message = event.getFormattedMessage();
        if (message == null) {
            return FilterReply.NEUTRAL;
        }
        for (final String noise : NOISE_SUBSTRINGS) {
            if (message.contains(noise)) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
