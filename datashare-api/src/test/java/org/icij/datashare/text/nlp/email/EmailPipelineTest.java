package org.icij.datashare.text.nlp.email;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.NlpTag;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        String content = "this is a content with email@domain.com";
        Annotations annotations = emailPipeline.process(content, "docId", Language.ENGLISH);

        assertThat(annotations.get(NlpStage.NER)).hasSize(1);
        NlpTag nlpTag = annotations.get(NlpStage.NER).get(0);
        assertThat(nlpTag.getBegin()).isEqualTo(23);
        assertThat(nlpTag.getEnd()).isEqualTo(39);
        assertThat(nlpTag.getCategory()).isEqualTo(NamedEntity.Category.EMAIL);
        assertThat(content.substring(nlpTag.getBegin(), nlpTag.getEnd())).isEqualTo("email@domain.com");
    }

    @Test
    public void test_one_email_twice() {
        String content = "this is a content with email@domain.com\n" +
                "that is twice in the document\n" +
                "email@domain.com";
        Annotations annotations = emailPipeline.process(content, "docId", Language.ENGLISH);

        assertThat(annotations.get(NlpStage.NER)).hasSize(2);

        NlpTag nlpTag = annotations.get(NlpStage.NER).get(1);
        assertThat(nlpTag.getBegin()).isEqualTo(70);
        assertThat(nlpTag.getEnd()).isEqualTo(86);
        assertThat(content.substring(nlpTag.getBegin(), nlpTag.getEnd())).isEqualTo("email@domain.com");
    }

    @Test
    public void test_three_emails() {
        Annotations annotations = emailPipeline.process("this is a content with email@domain.com\n" +
                "and another one : foo@bar.com\n" +
                "and baz@qux.fr", "docId", Language.ENGLISH);

        assertThat(annotations.get(NlpStage.NER)).hasSize(3);
    }

    @Test
    public void test_acceptance() throws IOException {
        Path emailFile = Paths.get(getClass().getResource("/email.eml").getPath());
        String content = new String(Files.readAllBytes(emailFile));

        Annotations annotations = emailPipeline.process(content, "docId", Language.ENGLISH);

        assertThat(annotations.get(NlpStage.NER)).hasSize(10);
        assertThat(NamedEntity.allFrom(content, annotations)).hasSize(10);
    }
}
