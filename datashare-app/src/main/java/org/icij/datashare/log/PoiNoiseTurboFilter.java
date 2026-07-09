package org.icij.datashare.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Drops benign, high-volume Apache POI parsing messages emitted for many legacy Office
 * files (WMF text metrics, PowerPoint text atoms and style runs, Visio chunk offsets, Word
 * paragraph tables, Outlook attachment chunks, HPSF code pages and spreadsheet number formats).
 * Message-targeted (like extract's PdfBoxNoiseFilter) rather than logger-level, so genuine POI
 * corruption diagnostics keep flowing and nothing at ERROR is ever dropped.
 *
 * <p>Each prefix matches the stable text before POI's first {@code {}} placeholder, so it works
 * whether logback receives the raw pattern or the already-formatted message from the log4j bridge.
 */
public class PoiNoiseTurboFilter extends TurboFilter {
    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (format == null || level.isGreaterOrEqual(Level.ERROR)) {
            return FilterReply.NEUTRAL;
        }
        return switch (logger.getName()) {
            case "org.apache.poi.hwmf.record.HwmfText" ->
                    deny(format.startsWith("META_EXTTEXTOUT"));
            case "org.apache.poi.hslf.usermodel.HSLFTextParagraph" ->
                    deny(format.startsWith("MasterSheet is not available")
                            || format.startsWith("bytes nor chars atom doesn't exist"));
            case "org.apache.poi.hslf.record.Record" ->
                    deny(format.startsWith("Problem reading paragraph style runs")
                            || format.startsWith("Problem reading character style runs"));
            case "org.apache.poi.hdgf.chunks.Chunk" ->
                    deny(format.startsWith("Command offset"));
            case "org.apache.poi.hslf.model.textproperties.BitMaskTextProp" ->
                    deny(format.startsWith("Style properties of"));
            case "org.apache.poi.hwpf.model.PAPBinTable" ->
                    deny(format.startsWith("Paragraph ["));
            case "org.apache.poi.hsmf.datatypes.AttachmentChunks" ->
                    deny(format.startsWith("Currently unsupported attachment chunk property"));
            case "org.apache.poi.hpsf.CodePageString" ->
                    deny(format.startsWith("CodePageString started at offset"));
            case "org.apache.poi.ss.usermodel.DataFormatter" ->
                    deny(format.startsWith("Formatting failed for format"));
            default -> FilterReply.NEUTRAL;
        };
    }

    private static FilterReply deny(boolean matches) {
        return matches ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
