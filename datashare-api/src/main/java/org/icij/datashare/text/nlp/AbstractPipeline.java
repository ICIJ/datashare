package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.*;

import static org.icij.datashare.function.ThrowingFunctions.*;
import static org.icij.datashare.text.nlp.Pipeline.Type.valueOf;


public abstract class AbstractPipeline implements Pipeline {
    public static final String NLP_STAGES_PROP = "nlpStages";
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    protected final Charset encoding;
    protected final List<NamedEntity.Category> targetEntities;
    protected final boolean caching;

    protected AbstractPipeline(Properties properties) {
        targetEntities = getProperty(Property.ENTITIES.getName(), properties,
                removeSpaces
                        .andThen(splitComma)
                        .andThen(NamedEntity.Category.parseAll))
                .orElse(DEFAULT_ENTITIES);

        encoding = getProperty(Property.ENCODING.getName(), properties,
                parseCharset.compose(String::trim))
                .orElse(DEFAULT_ENCODING);

        caching = getProperty(Property.CACHING.getName(), properties,
                trim.andThen(Boolean::parseBoolean))
                .orElse(DEFAULT_CACHING);
    }

    @Override
    public Type getType() { return Type.fromClassName(getClass().getSimpleName()).get(); }

    @Override
    public List<NamedEntity.Category> getTargetEntities() { return targetEntities; }

    @Override
    public boolean isCaching() { return caching; }

    @Override
    public Charset getEncoding() { return encoding; }

    public static AbstractPipeline create(final String pipelineName, final PropertiesProvider propertiesProvider) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException, ClassNotFoundException {
        Class<? extends AbstractPipeline> pipelineClass = (Class<? extends AbstractPipeline>) Class.forName(valueOf(pipelineName).getClassName());
        return pipelineClass.getDeclaredConstructor(PropertiesProvider.class).newInstance(propertiesProvider);
    }

    public boolean initialize(Language language) throws InterruptedException {
        if (!supports(language)) {
            LOGGER.info("initializing {}, skipping as {} is not supported", getType(), language);
            return false;
        }
        LOGGER.info("initializing {}", getType());
        return true;
    }

    /**
     * Apply all specified stages/annotators on input
     *  @param doc is the document source to process */
    public abstract List<NamedEntity> process(Document doc) throws InterruptedException;

    /**
     * Post-processing operations
     */
    public void terminate(Language language) throws InterruptedException {
        LOGGER.info("ending {} {}", getType(), language);
    }

    /**
     * @return Language . NlpStage support matrix
     */
    public abstract Set<Language> supportedLanguages();

    @Override
    public boolean supports(Language language) {
        return supportedLanguages().contains(language);
    }
}
