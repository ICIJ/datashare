package org.icij.datashare.ftm;

import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class FtmDocumentTest  {
    @Test
    public void test_translated_language_empty_list() {
        FtmDocument doc = new FtmDocument(DocumentBuilder.createDoc("docId").with(Language.FRENCH).with("un doc en français").with(new ArrayList<>()).build());
        assertThat(doc.getTranslatedLanguage()).isNull();
    }

    @Test
    public void test_translated_language() {
        FtmDocument doc = new FtmDocument(DocumentBuilder.createDoc("docId").with(Language.FRENCH).with("un doc en français").with(
                List.of(Map.of(
                        "translator", "ARGOS",
                        "source_language", "FRENCH",
                        "target_language", "ENGLISH",
                        "content", "a french doc"))
        ).build());
        assertThat(doc.getTranslatedLanguage()).isEqualTo("ENGLISH");
    }

    @Test
    public void test_translated_content() {
        FtmDocument doc = new FtmDocument(DocumentBuilder.createDoc("docId").with(Language.FRENCH).with("un doc en français").with(
                List.of(
                        Map.of(
                        "translator", "ARGOS",
                        "source_language", "FRENCH",
                        "target_language", "ENGLISH",
                        "content", "a french"),
                Map.of(
                        "translator", "ARGOS",
                        "source_language", "FRENCH",
                        "target_language", "ENGLISH",
                        "content", " doc"))
        ).build());
        assertThat(doc.getTranslatedText()).isEqualTo("a french doc");
    }
}