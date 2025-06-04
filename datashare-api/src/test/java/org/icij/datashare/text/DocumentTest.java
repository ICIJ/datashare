package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Document.nerMask;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.nlp.DocumentMetadataConstants.DEFAULT_VALUE_UNKNOWN;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.icij.datashare.text.nlp.Pipeline.Type.OPENNLP;
import static org.icij.datashare.text.nlp.Pipeline.set;

public class DocumentTest {
    @Test
    public void test_json_deserialize() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(createDoc("content").build())).contains("\"projectId\":\"prj\"");
        assertThat(JsonObjectMapper.MAPPER.readValue(("{\"id\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"path\":\"file:///home/dev/src/datashare/datashare-api/path\"," +
                        "\"dirname\":\"/home/dev/src/datashare/datashare:api/\"," +
                        "\"content\":\"content\",\"language\":\"FRENCH\"," +
                        "\"extractionDate\":\"2019-05-09T16:12:17.589Z\",\"contentEncoding\":\"UTF-8\"," +
                        "\"contentType\":\"text/plain\",\"extractionLevel\":0," +
                        "\"metadata\":{},\"status\":\"INDEXED\",\"nerTags\":[]," +
                        "\"parentDocument\":null,\"rootDocument\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"contentLength\":123,\"projectId\":\"prj\", \"tags\": [\"foo\", \"bar\"]}").getBytes(),
                Document.class).getProject()).isEqualTo(project("prj"));
    }

    @Test
    public void test_serialize_contains_content_text_length() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(createDoc("content").build())).contains("\"contentTextLength\":7");
    }

    @Test
    public void test_json_deserialize_path_and_dirname_with_special_chars() throws Exception {
        String path = "/dir/to/docs/shared/foo/Data/2018-05/My Administrations/TGA_BAR/htmls/responses3/3#19221-Arrow CVC SET: 3-CORP 4FR X 8CM - Qux, blah, infusion, central portal|Pouet International Inc.html";

        Document document = JsonObjectMapper.MAPPER.readValue(("{\"id\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"path\":\"" + path + "\"," +
                        "\"dirname\":\"" + get(path).getParent() + "\"," +
                        "\"content\":\"content\",\"language\":\"FRENCH\"," +
                        "\"extractionDate\":\"2019-05-09T16:12:17.589Z\",\"contentEncoding\":\"UTF-8\"," +
                        "\"contentType\":\"text/plain\",\"extractionLevel\":0," +
                        "\"metadata\":{},\"status\":\"INDEXED\",\"nerTags\":[]," +
                        "\"parentDocument\":null,\"rootDocument\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"contentLength\":123,\"projectId\":\"prj\", \"tags\": [\"foo\", \"bar\"]}").getBytes(),
                Document.class);

        assertThat(document.getPath().toString()).isEqualTo(path);
        assertThat(document.getDirname().toString()).isEqualTo(get(path).getParent().toString());
    }

    @Test
    public void test_ner_mask() {
        assertThat(nerMask(new HashSet<>())).isEqualTo((short) 0);
        assertThat(nerMask(set(CORENLP))).isEqualTo((short) 1);
        assertThat(nerMask(set(OPENNLP, CORENLP))).isEqualTo((short) 17);
    }

    @Test
    public void test_from_mask() {
        assertThat(Document.fromNerMask(0)).isEmpty();
        assertThat(Document.fromNerMask(1)).contains(CORENLP);
        assertThat(Document.fromNerMask(5)).contains(CORENLP);
        assertThat(Document.fromNerMask(31)).contains(CORENLP, OPENNLP);
    }

    @Test
    public void test_get_creation_date_without_metadata() {
        assertThat(createDoc("name").with((Map<String, Object>)null).build().getCreationDate()).isNull();
    }

    @Test
    public void test_creation_date_without_zone() {
        assertThat(createDoc("name").with(new HashMap<>() {{
            put("tika_metadata_dcterms_created", "2019-02-04T11:37:30.368441317");}}).build().getCreationDate()).isNotNull();
    }

    @Test
    public void test_creation_date_zoned() {
        assertThat(createDoc("name").with(new HashMap<>() {{
            put("tika_metadata_dcterms_created", "2019-02-04T11:37:30Z");}}).build().getCreationDate()).isNotNull();
    }

    @Test
    public void test_creation_date_unparseable() {
        assertThat(createDoc("name").with(new HashMap<>() {{
            put("tika_metadata_dcterms_created", "not a date");}}).build().getCreationDate()).isNull();
    }


    @Test
    public void test_title_and_title_norm() {
        assertThat(createDoc("name").with(new HashMap<>() {{
            put("tika_metadata_resourcename", "Document Title");
        }}).build().getTitle()).isEqualTo("Document Title");

        assertThat(createDoc("name").with(new HashMap<>() {{
            put("tika_metadata_resourcename", "Document Title");
        }}).build().getTitleNorm()).isEqualTo("document title");

        assertThat(createDoc("name").build().getTitle()).isEqualTo(DEFAULT_VALUE_UNKNOWN);
    }

    @Test
    public void test_title_for_json_tweet() {
        Document tweet = createDoc("name").ofContentType("application/json; twint").with(new HashMap<>() {{
            put("tika_metadata_dc_title", "Tweet Title");
        }}).build();
        assertThat(tweet.isJson()).isTrue();
        assertThat(tweet.getTitle()).isEqualTo("Tweet Title");

        assertThat(createDoc("name").ofContentType("application/json; twint").with(new HashMap<>() {{
            put("tika_metadata_resourcename", "Tweet Title");
        }}).build().getTitle()).isEqualTo("Tweet Title");
    }

    @Test
    public void test_title_for_email() {
        Document email = createDoc("name").ofContentType("message/rfc822").with(new HashMap<>() {{
            put("tika_metadata_dc_title", "Email Title");
        }}).build();
        assertThat(email.isEmail()).isTrue();
        assertThat(email.getTitle()).isEqualTo("Email Title");

        assertThat(createDoc("name").ofContentType("message/rfc822").with(new HashMap<>() {{
            put("tika_metadata_dc_subject", "Mail Title");
        }}).build().getTitle()).isEqualTo("Mail Title");

        assertThat(createDoc("name").ofContentType("message/rfc822").with(new HashMap<>() {{
            put("tika_metadata_resourcename", "Mail Title");
        }}).build().getTitle()).isEqualTo("Mail Title");
    }

    @Test
    public void test_content_translated() throws Exception {
        Map<String, String> english = new HashMap<>();
        english.put("content","hello world");
        english.put("target_language","ENGLISH");
        List<Map<String,String>> content_translated = new ArrayList<>(){{add(english); }};
        Document doc = createDoc("name").with(content_translated).build();
        assertThat(doc.getContentTranslated()).isNotNull();
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(doc)).contains("\"content_translated\":[{\"target_language\":\"ENGLISH\",\"content\":\"hello world\"}]");
    }

    @Test
    public void test_get_extractor_version() {
        Document email = createDoc("name").ofContentType("message/rfc822").with(new HashMap<>() {{
            put("tika_metadata_tika_version", "Apache Tika 3.1.0");
        }}).build();
        assertThat(email.getExtractorVersion()).isEqualTo("Apache Tika 3.1.0");
    }

    @Test
    public void test_get_extractor_version_from_history_records() {
        Document email = createDoc("name").ofContentType("message/rfc822").extractedAt(Date.from(Instant.parse("2019-05-09T16:12:17.589Z"))).build();
        assertThat(email.getExtractorVersion()).isEqualTo("Apache Tika 1.18.0");
    }

    @Test
    public void test_get_tika_version_record() {
        assertThat(Document.getTikaVersion(new Date(0))).isEqualTo("Apache Tika 1.8.0");
        assertThat(Document.getTikaVersion(Date.from(Instant.parse("2016-05-24T15:29:30Z")))).isEqualTo("Apache Tika 1.12.0");
        assertThat(Document.getTikaVersion(Date.from(Instant.parse("2016-05-24T15:29:32Z")))).isEqualTo("Apache Tika 1.13.0");
    }

    @Test
    public void test_serialize_contains_content_translated() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(createDoc("content").build())).contains("\"content_translated\":[]");
    }
}
