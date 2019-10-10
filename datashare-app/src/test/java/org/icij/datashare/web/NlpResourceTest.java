package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.convert.TypeConvert;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class NlpResourceTest implements FluentRestTest {
    @Mock
    AbstractPipeline pipeline;
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        doReturn(true).when(pipeline).initialize(any());
        OptimaizeLanguageGuesser languageGuesser = new OptimaizeLanguageGuesser();
        NlpResource nlpResource = new NlpResource(new PropertiesProvider(), languageGuesser, s -> pipeline);
        server.configure(routes -> routes.add(nlpResource));
    }

    @Test
    public void test_post_empty_text() throws Exception {
        doReturn(new Annotations("inline", CORENLP, ENGLISH)).when(pipeline).process(anyString(), anyString(), any());
        post("/ner/findNames/CORENLP", "").should().respond(200).contain("[]");

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).process("", "inline", ENGLISH);
    }

    @Test
    public void test_post_text_returns_NamedEntity_list() throws Exception {
        final Annotations annotations = new Annotations("inline", CORENLP, ENGLISH);
        annotations.add(NlpStage.NER, 10, 13, NamedEntity.Category.PERSON);
        doReturn(annotations).when(pipeline).process(anyString(), eq("inline"), any());

        Response response = post("/ner/findNames/CORENLP", "This the 'foù' file content.").response();

        List actualNerList = TypeConvert.fromJson(response.content(), List.class);
        assertThat(actualNerList).hasSize(1);
        assertThat(actualNerList.get(0)).isInstanceOf(HashMap.class);
        assertThat((Map) actualNerList.get(0)).includes(
                entry("mention", "foù"),
                entry("extractor", "CORENLP"),
                entry("mentionNorm", "fou"),
                entry("offset", 10)
        );
    }

    @Override
    public int port() { return server.port();}
}
