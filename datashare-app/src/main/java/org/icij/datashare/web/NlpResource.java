package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.annotations.Post;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.icij.datashare.text.NamedEntity.allFrom;


public class NlpResource {
    private final PropertiesProvider propertiesProvider;
    private final LanguageGuesser languageGuesser;

    @Inject
    public NlpResource(final PropertiesProvider propertiesProvider, final LanguageGuesser languageGuesser) {
        this.propertiesProvider = propertiesProvider;
        this.languageGuesser = languageGuesser;
    }

    @Post("/ner/findNames/:pipeline")
    public List<NamedEntity> getAnnotations(final String pipeline, String text) throws Exception {
        AbstractPipeline p = createPipeline(pipeline);
        Language language = languageGuesser.guess(text);
        if (p.initialize(language)) {
            Annotations annotations = p.process(text, "inline", language);
            return allFrom(text, annotations);
        }
        return emptyList();
    }

    protected AbstractPipeline createPipeline(final String pipelineName) throws InvocationTargetException,
            NoSuchMethodException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        return AbstractPipeline.create(pipelineName, propertiesProvider);
    }
}
