package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.icij.datashare.text.NamedEntity.allFrom;

@Prefix("/ner")
public class NlpResource {
    private final PropertiesProvider propertiesProvider;
    private final LanguageGuesser languageGuesser;
    private final ThrowingFunction<String, AbstractPipeline> pipelineCreator;

    @Inject
    public NlpResource(final PropertiesProvider propertiesProvider, final LanguageGuesser languageGuesser) {
        this.propertiesProvider = propertiesProvider;
        this.languageGuesser = languageGuesser;
        this.pipelineCreator = this::createPipeline;
    }

    NlpResource(final PropertiesProvider propertiesProvider, final LanguageGuesser languageGuesser, ThrowingFunction<String, AbstractPipeline> pipelineCreator) {
        this.propertiesProvider = propertiesProvider;
        this.languageGuesser = languageGuesser;
        this.pipelineCreator = pipelineCreator;
    }

    /**
     * When datashare is launched in NER mode (without index) it exposes a name finding HTTP API. The text is sent with the HTTP body.
     *
     * @param pipeline to use
     * @param text to analyse
     * @return
     *
     * Example :
     * $(curl -XPOST http://dsenv:8080/ner/findNames/CORENLP -d "Please find attached a PDF copy of the advance tax clearance obtained for our client")
     */
    @Post("/findNames/:pipeline")
    public List<NamedEntity> getAnnotations(final String pipeline, String text) throws Exception {
        AbstractPipeline p = pipelineCreator.apply(pipeline);
        Language language = languageGuesser.guess(text);
        if (p.initialize(language)) {
            Annotations annotations = p.process(text, "inline", language);
            return allFrom(text, annotations);
        }
        return emptyList();
    }

    private AbstractPipeline createPipeline(final String pipelineName) throws InvocationTargetException,
            NoSuchMethodException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        return AbstractPipeline.create(pipelineName, propertiesProvider);
    }
}
