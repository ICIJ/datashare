package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static java.util.Collections.emptyList;

@Singleton
@Prefix("/api/ner")
public class NerResource {
    private final PipelineRegistry pipelineRegistry;
    private final LanguageGuesser languageGuesser;

    @Inject
    public NerResource(final PipelineRegistry pipelineRegistry, final LanguageGuesser languageGuesser) {
        this.pipelineRegistry = pipelineRegistry;
        this.languageGuesser = languageGuesser;
    }

    /**
     * Get the list of registered pipelines.
     *
     * @return pipeline set
     * Example:
     * $(curl http://dsenv:8080/api/ner/pipelines)
     */
    @Get("/pipelines")
    public Set<Pipeline.Type> getRegisteredPipelines() {
        return pipelineRegistry.getPipelineTypes();
    }

    /**
     * When datashare is launched in NER mode (without index) it exposes a name finding HTTP API. The text is sent with the HTTP body.
     *
     * @param pipeline to use
     * @param text to analyse in the request body
     * @return list of NamedEntities annotations
     *
     * Example :
     * $(curl -XPOST http://dsenv:8080/api/ner/findNames/CORENLP -d "Please find attached a PDF copy of the advance tax clearance obtained for our client John Doe.")
     */
    @Post("/findNames/:pipeline")
    public List<NamedEntity> getAnnotations(final String pipeline, String text) throws Exception {
        LoggerFactory.getLogger(getClass()).info(String.valueOf(getClass().getClassLoader()));
        Pipeline p = pipelineRegistry.get(Pipeline.Type.parse(pipeline));
        Language language = languageGuesser.guess(text);
        if (p.initialize(language)) {
            return p.process(DocumentBuilder.createDoc("inline").with(text).with(language).build());
        }
        return emptyList();
    }
}
