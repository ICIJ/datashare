package org.icij.datashare.text.nlp.email;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;


public class EmailPipelineTest {
    private final EmailPipeline emailPipeline = new EmailPipeline(new PropertiesProvider());

    @Test
    public void test_no_email() {
        Annotations annotations = emailPipeline.process("this is a content without email but with an arobase (@).", "docId", Language.ENGLISH);

        assertThat(annotations.getDocumentId()).isEqualTo("docId");
        assertThat(annotations.getLanguage()).isEqualTo(Language.ENGLISH);
        assertThat(annotations.get(NlpStage.NER)).isEmpty();
    }

    @Test
    public void test_one_email() {
        Annotations annotations = emailPipeline.process("this is a content with email@domain.com", "docId", Language.ENGLISH);

        assertThat(annotations.get(NlpStage.NER)).hasSize(1);
        assertThat(annotations.get(NlpStage.NER).get(0).getBegin()).isEqualTo(23);
        assertThat(annotations.get(NlpStage.NER).get(0).getEnd()).isEqualTo(39);
        assertThat(annotations.get(NlpStage.NER).get(0).getValue()).isEqualTo("email@domain.com");
    }

    @Test
    public void test_one_email_twice() {
        Annotations annotations = emailPipeline.process("this is a content with email@domain.com\n" +
                "that is twice in the document\n" +
                "email@domain.com", "docId", Language.ENGLISH);

        assertThat(annotations.get(NlpStage.NER)).hasSize(2);

        assertThat(annotations.get(NlpStage.NER).get(1).getBegin()).isEqualTo(70);
        assertThat(annotations.get(NlpStage.NER).get(1).getEnd()).isEqualTo(86);
        assertThat(annotations.get(NlpStage.NER).get(1).getValue()).isEqualTo("email@domain.com");
    }

    @Test
    public void test_three_emails() {
        Annotations annotations = emailPipeline.process("this is a content with email@domain.com\n" +
                "and another one : foo@bar.com\n" +
                "and baz@qux.fr", "docId", Language.ENGLISH);

        assertThat(annotations.get(NlpStage.NER)).hasSize(3);
        assertThat(annotations.get(NlpStage.NER).get(0).getValue()).isEqualTo("email@domain.com");
        assertThat(annotations.get(NlpStage.NER).get(1).getValue()).isEqualTo("foo@bar.com");
        assertThat(annotations.get(NlpStage.NER).get(2).getValue()).isEqualTo("baz@qux.fr");
    }
}
