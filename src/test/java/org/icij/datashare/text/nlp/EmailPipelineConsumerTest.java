package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.email.EmailPipeline;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.NamedEntity.Category.EMAIL;
import static org.icij.datashare.text.nlp.email.EmailPipeline.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class EmailPipelineConsumerTest {
    @Mock
    private Indexer indexer;
    private NlpConsumer nlpListener;
    private EmailPipeline pipeline = new EmailPipeline(new PropertiesProvider());

    @Before
    public void setUp() {
        initMocks(this);
        nlpListener = new NlpConsumer(pipeline, indexer, null);
    }

    @Test
    public void test_adds_document_headers_parsing_for_email() throws Exception {
        Document doc = createDoc("docid").with("hello@world.com").ofMimeType("message/rfc822").with(new HashMap<String, Object>() {{
            put(tikaMsgHeader("To"), "email1@domain.com");
            put(tikaMsgHeader("Cc"), "email2@domain.com");
        }}).build();
        when(indexer.get("projectName", doc.getId(), "routing")).thenReturn(doc);

        nlpListener.findNamedEntities("projectName", doc.getId(), "routing");

        verify(indexer).bulkAdd("projectName", Pipeline.Type.EMAIL,
                asList(
                        NamedEntity.create(EMAIL, "hello@world.com", 0, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "email2@domain.com", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "email1@domain.com", -1, "docid", Pipeline.Type.EMAIL, FRENCH)
                        ), doc);
    }

    @Test
    public void test_filter_headers_that_contains_mail_addresses() throws Exception {
        Document doc = createDoc("docid").with("mail content").ofMimeType("message/rfc822").with(new HashMap<String, Object>() {{
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
        when(indexer.get("projectName", doc.getId(), "routing")).thenReturn(doc);

        nlpListener.findNamedEntities("projectName", doc.getId(), "routing");

        verify(indexer).bulkAdd("projectName", Pipeline.Type.EMAIL,
                asList(
                        NamedEntity.create(EMAIL, "replyto@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "alternate@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "resent-sender@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "cc@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "from@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "resent-cc@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "forhandling@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "resent-replyto@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "return@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "resent-to@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "followup@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "resent-bcc@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "subject@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "bcc@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "to@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(EMAIL, "resent-from@head.er", -1, "docid", Pipeline.Type.EMAIL, FRENCH)
                ), doc);
    }
}
