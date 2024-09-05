package org.icij.datashare.nlp;

import java.util.Objects;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.nlp.EmailPipeline.*;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.NamedEntity.Category.EMAIL;

public class EmailPipelineTest {
    private final EmailPipeline pipeline = new EmailPipeline(new PropertiesProvider());
    @Test
    public void test_no_email() throws InterruptedException {
        List<NamedEntity> namedEntities = pipeline.processDoc(createDocument("this is a content without email but with an arobase (@).", "docId", Language.ENGLISH));
        assertThat(namedEntities).isEmpty();
    }

    private Document createDocument(String content, String docId, Language language) {
        return createDoc(docId).with(content).with(language).build();
    }

    @Test
    public void test_one_email() throws InterruptedException {
        String content = "this is a content with email@domain.com";
        List<NamedEntity> nameEntities = pipeline.processDoc(createDocument(content, "docId", Language.ENGLISH));

        assertThat(nameEntities).hasSize(1);
        assertThat(nameEntities.get(0).getOffsets()).containsExactly(23L);
        assertThat(nameEntities.get(0).getCategory()).isEqualTo(NamedEntity.Category.EMAIL);
        assertThat(nameEntities.get(0).getMention()).isEqualTo("email@domain.com");
    }

    @Test
    public void test_one_email_twice() throws InterruptedException {
        String content = """
this is a content with email@domain.com
that is twice in the document
email@domain.com""";
        List<NamedEntity> namedEntities = pipeline.processDoc(createDocument(content, "docId", Language.ENGLISH));

        assertThat(namedEntities).hasSize(1);
        NamedEntity nlpTag = namedEntities.get(0);
        assertThat(nlpTag.getOffsets()).containsExactly(23L, 70L);
        assertThat(nlpTag.getMention()).isEqualTo("email@domain.com");
    }

    @Test
    public void test_three_emails() throws InterruptedException {
        List<NamedEntity> namedEntities = pipeline.processDoc(createDocument("""
this is a content with email@domain.com
and another one : foo@bar.com
and baz@qux.fr""", "docId", Language.ENGLISH));

        assertThat(namedEntities).hasSize(3);
    }

    @Test
    public void test_emails_chunked_content() {
        Document document = createDocument("""
this is a content with email@domain.com
and another one : foo@bar.com
and baz@qux.fr""", "docId", Language.ENGLISH);
        List<NamedEntity> namedEntities = pipeline.processDoc(document, 20, 72);

        assertThat(namedEntities).hasSize(1);
        assertThat(namedEntities.get(0).getMention()).isEqualTo("baz@qux.fr");
        assertThat(namedEntities.get(0).getOffsets()).containsExactly(74L);
    }

    @Test
    public void test_acceptance() throws IOException, InterruptedException {
        Path emailFile = Paths.get(Objects.requireNonNull(getClass().getResource("/email.eml")).getPath());
        String content = new String(Files.readAllBytes(emailFile));

        List<NamedEntity> namedEntities = pipeline.processDoc(createDocument(content, "docId", Language.ENGLISH));

        assertThat(namedEntities).hasSize(3);
        assertThat(namedEntities.get(0).getOffsets()).containsExactly(14L, 48L, 168L, 332L, 1283L, 1482L, 1544L, 1582L);
    }

    @Test
    public void test_adds_document_headers_parsing_for_email() throws InterruptedException {
        Document doc = createDoc("docid")
            .with("hello@world.com")
            .withRootId("root")
            .with(FRENCH)
            .ofContentType("message/rfc822")
            .with(new HashMap<>() {{
                put(tikaMsgHeader("To"), "email1@domain.com");
                put(tikaMsgHeader("Cc"), "email2@domain.com,email3@domain.com");
            }}).build();

        List<NamedEntity> namedEntities = pipeline.processDoc(doc);

        Map<String, Object> metaFirst = Map.of("emailHeaderField", "tika_metadata_message_to");
        NamedEntity fourth = NamedEntity.create(
            EMAIL, "email1@domain.com", List.of(-1L), "docid", "root", Pipeline.Type.EMAIL, FRENCH, metaFirst
        );
        Map<String, Object> metaSecond = Map.of("emailHeaderField", "tika_metadata_message_cc");
        NamedEntity second = NamedEntity.create(
            EMAIL, "email2@domain.com", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, metaSecond
        );
        NamedEntity third = NamedEntity.create(
            EMAIL, "email3@domain.com", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, metaSecond
        );

        assertThat(namedEntities).containsExactly(
            NamedEntity.create(EMAIL, "hello@world.com", List.of(0L), "docid", "root", Pipeline.Type.EMAIL, FRENCH),
            second,
            third,
            fourth
        );
    }

    @Test
    public void test_filter_headers_that_contains_mail_addresses() throws InterruptedException {
        Document doc = createDoc("docid")
            .with("mail content")
            .ofContentType("message/rfc822")
            .with(FRENCH)
            .withRootId("root")
            .with(new HashMap<>() {{
                put(tikaRawHeader("field"), "email@domain.com");
                put(tikaRawHeader("Message-ID"), "id@domain.com");
                put(tikaRawHeader("Return-Path"), "return@head.er");
                put(tikaMsgHeader("To"), "to@head.er");
                put(tikaMsgHeader("From"), "from@head.er");
                put(tikaMsgHeader("Cc"), "cc@head.er");
                put(tikaMsgHeader("Bcc"), "bcc@head.er");
                put(tika("Dc-Title"), "subject@head.er");
                put(tikaRawHeader("Reply-To"), "replyto@head.er");
                put(tikaRawHeader("Followup-To"), "followup@head.er");
                put(tikaRawHeader("Alternate-Recipient"), "alternate@head.er");
                put(tikaRawHeader("For-Handling"), "forhandling@head.er");
                put(tikaRawHeader("Resent-Reply-To"), "resent-replyto@head.er");
                put(tikaRawHeader("Resent-Sender"), "resent-sender@head.er");
                put(tikaRawHeader("Resent-From"), "resent-from@head.er");
                put(tikaRawHeader("Resent-To"), "resent-to@head.er");
                put(tikaRawHeader("Resent-cc"), "resent-cc@head.er");
                put(tikaRawHeader("Resent-bcc"), "resent-bcc@head.er");
            }}).build();

        List<NamedEntity> namedEntities = pipeline.processDoc(doc);

        assertThat(namedEntities).containsExactly(
                        NamedEntity.create(EMAIL, "replyto@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_reply_to")),
                        NamedEntity.create(EMAIL, "alternate@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_alternate_recipient")),
                        NamedEntity.create(EMAIL, "resent-sender@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_resent_sender")),
                        NamedEntity.create(EMAIL, "cc@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_cc")),
                        NamedEntity.create(EMAIL, "from@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_from")),
                        NamedEntity.create(EMAIL, "resent-cc@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_resent_cc")),
                        NamedEntity.create(EMAIL, "forhandling@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_for_handling")),
                        NamedEntity.create(EMAIL, "resent-replyto@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_resent_reply_to")),
                        NamedEntity.create(EMAIL, "return@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_return_path")),
                        NamedEntity.create(EMAIL, "resent-to@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_resent_to")),
                        NamedEntity.create(EMAIL, "followup@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_followup_to")),
                        NamedEntity.create(EMAIL, "resent-bcc@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_resent_bcc")),
                        NamedEntity.create(EMAIL, "subject@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_dc_title")),
                        NamedEntity.create(EMAIL, "bcc@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_bcc")),
                        NamedEntity.create(EMAIL, "to@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_to")),
                        NamedEntity.create(EMAIL, "resent-from@head.er", List.of(-1L), "docid", "root", Type.EMAIL, FRENCH, Map.of("emailHeaderField", "tika_metadata_message_raw_header_resent_from"))
        );
    }
}
