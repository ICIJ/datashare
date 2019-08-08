package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.email.EmailPipeline;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class EmailPipelineConsumerTest {
    @Mock private Indexer indexer;
    private NlpConsumer nlpListener;
    private EmailPipeline pipeline = new EmailPipeline(new PropertiesProvider());

    @Before
    public void setUp() {
        initMocks(this);
        nlpListener = new NlpConsumer(pipeline, indexer,  null);
    }

    @Test
    public void test_on_message_processNLP__when_doc_found_in_index() throws Exception {
        Document doc = createDoc("hello@world.com", new HashMap<String, Object>() {{
            put("field1", "email1@domain.com");
            put("field2", "email2@domain.com");
        }});
        when(indexer.get("projectName", doc.getId(), "routing")).thenReturn(doc);

        nlpListener.findNamedEntities("projectName", doc.getId(), "routing");

        verify(indexer).bulkAdd("projectName", Pipeline.Type.EMAIL,
                asList(
                        NamedEntity.create(NamedEntity.Category.EMAIL, "hello@world.com", 0, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(NamedEntity.Category.EMAIL, "email1@domain.com", -1, "docid", Pipeline.Type.EMAIL, FRENCH),
                        NamedEntity.create(NamedEntity.Category.EMAIL, "email2@domain.com", -1, "docid", Pipeline.Type.EMAIL, FRENCH)
                ), doc);
    }

    private Document createDoc(String name, Map<String, Object> metadata) {
            return new Document(project("prj"), "docid", Paths.get("/path/to/").resolve(name), name,
                    FRENCH, Charset.defaultCharset(),
                    "message/rfc822", metadata, Document.Status.INDEXED,
                    new HashSet<>(), new Date(), null, null,
                    0, 123L);
        }
}
