package org.icij.datashare.text.indexing.elasticsearch;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_OCR_MIN_CONFIDENCE;

/**
 * Options of the language detection, shared by the stages that run it: the INDEX stage detects at index time and the
 * LANGUAGE stage re-detects, and a re-run must not downgrade what index time got right.
 * @param defaultProject name of the project whose documents are processed
 * @param ocrMinConfidence OCR confidence, from 0 to 100, under which an image's text is not used to detect its
 *                         language. 0 disables the rule, so a null (absent) value is the default, not 0.
 */
public record LanguageOptions(String defaultProject, Integer ocrMinConfidence) {
    public LanguageOptions {
        defaultProject = ofNullable(defaultProject).orElse(DEFAULT_DEFAULT_PROJECT);
        ocrMinConfidence = ofNullable(ocrMinConfidence).orElse(DEFAULT_OCR_MIN_CONFIDENCE);
    }
}
