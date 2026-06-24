package org.icij.datashare.cli;

import org.apache.tika.parser.pdf.PDFParserConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.fest.assertions.Assertions.assertThat;

public class OcrStrategyTest {
    @Test
    public void test_ocr_strategy_names_match_tika_enum() {
        Set<String> datashareNames = Arrays.stream(OcrStrategy.values())
                .map(Enum::name).collect(Collectors.toSet());
        Set<String> tikaNames = Arrays.stream(PDFParserConfig.OCR_STRATEGY.values())
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(datashareNames).isEqualTo(tikaNames);
    }
}
