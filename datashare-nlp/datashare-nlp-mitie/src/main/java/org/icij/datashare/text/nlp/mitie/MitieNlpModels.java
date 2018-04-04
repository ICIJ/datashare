package org.icij.datashare.text.nlp.mitie;

import edu.mit.ll.mitie.EntityMentionVector;
import edu.mit.ll.mitie.NamedEntityExtractor;
import edu.mit.ll.mitie.StringVector;
import edu.mit.ll.mitie.TokenIndexVector;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.icij.datashare.text.nlp.NlpStage.NER;

public final class MitieNlpModels extends AbstractModels<NamedEntityExtractor> {
    private static volatile MitieNlpModels instance;
    private static final Object mutex = new Object();

    private final static String VERSION = "0.2";
    public static final String NER_MODEL_NAME = "ner_model.dat";
    private final Map<Language, StringVector> tagSet = new HashMap<>();

    public static MitieNlpModels getInstance() {
        MitieNlpModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new MitieNlpModels();
                }
            }
        }
        return instance;
    }

    private MitieNlpModels() {super(Pipeline.Type.MITIE, NER);}

    @Override
    protected NamedEntityExtractor loadModelFile(Language language, ClassLoader loader) throws IOException {
        return new NamedEntityExtractor(getModelsFilesystemPath(language).resolve(NER_MODEL_NAME).toAbsolutePath().toString());
    }

    public EntityMentionVector extract(TokenIndexVector tokens, Language language) throws InterruptedException {
        NamedEntityExtractor namedEntityExtractor = get(language);

        if (namedEntityExtractor != null) {
            Semaphore l = modelLock.get(language);
            l.acquire(); // TODO : is extractEntities not threadsafe ?
            try {
                return namedEntityExtractor.extractEntities(tokens);
            } finally {
                l.release();
            }
        } else {
            return new EntityMentionVector();
        }
    }

    @Override
    protected String getVersion() {return VERSION;}

    public StringVector getTagSet(Language language) {
        if (tagSet.containsKey(language))
            return tagSet.get(language);
        if (models.containsKey(language)) {
            tagSet.put(language, models.get(language).getPossibleNerTags());
            return tagSet.get(language);
        }
        return new StringVector();
    }
}
