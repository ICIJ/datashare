package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

    @Operation(description = "Gets the list of registered pipelines.")
    @ApiResponse(responseCode = "200", description = "returns the pipeline set", useReturnTypeSchema = true)
    @Get("/pipelines")
    public Set<Pipeline.Type> getRegisteredPipelines() {
        return pipelineRegistry.getPipelineTypes();
    }

    @Operation(description = """
            When datashare is launched in NER mode (without index) it exposes a name finding HTTP API.
            
            The text is sent with the HTTP body.""")
    @ApiResponse(responseCode = "200", description = "returns the list of NamedEntities annotations", useReturnTypeSchema = true)
    @Post("/findNames/:pipeline")
    public List<NamedEntity> getAnnotations(@Parameter(name = "pipeline", description = "pipeline to use", in = ParameterIn.PATH) final String pipeline,
                                            @Parameter(name = "text", description = "text to analyze in the request body", in = ParameterIn.QUERY) String text) throws Exception {
        LoggerFactory.getLogger(getClass()).info(String.valueOf(getClass().getClassLoader()));
        Pipeline p = pipelineRegistry.get(Pipeline.Type.parse(pipeline));
        Language language = languageGuesser.guess(text);
        if (p.initialize(language)) {
            return p.process(DocumentBuilder.createDoc("inline").with(text).with(language).build());
        }
        return emptyList();
    }
}
