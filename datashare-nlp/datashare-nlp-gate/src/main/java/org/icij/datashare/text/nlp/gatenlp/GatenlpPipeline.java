package org.icij.datashare.text.nlp.gatenlp;

import com.google.inject.Inject;
import es.upm.oeg.icij.entityextractor.GATENLPApplication;
import es.upm.oeg.icij.entityextractor.GATENLPDocument;
import es.upm.oeg.icij.entityextractor.GATENLPFactory;
import gate.AnnotationSet;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;

import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.*;


public final class GatenlpPipeline extends AbstractPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(TOKEN, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, NER)));
                put(FRENCH,  new HashSet<>(asList(TOKEN, NER)));
                put(GERMAN,  new HashSet<>(asList(TOKEN, NER)));
            }};

    private static final Map<NamedEntity.Category, String> GATE_NER_CATEGORY_NAME =
            new HashMap<NamedEntity.Category, String>(){{
                put(ORGANIZATION, "Company");
                put(PERSON,       "Person");
                put(LOCATION,     "Country");
            }};

    private static final Map<NlpStage, String> GATE_STAGE_NAME =
            new HashMap<NlpStage, String>(){{
                put(TOKEN,    "Token");
                put(SENTENCE, "Sentence");
            }};

    private GATENLPApplication pipeline;

    @Inject
    public GatenlpPipeline(PropertiesProvider propertiesProvider) {
        super(propertiesProvider.getProperties());
        stageDependencies.get(NER).add(TOKEN);
    }

    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    @Override
    public boolean initialize(Language language) throws InterruptedException {
        if ( ! super.initialize(language))
            return false;
        if (pipeline != null) {
            return true;
        }
        try {
            GateNlpModels gateNlpModels = new GateNlpModels();
            gateNlpModels.get(language);// just to download if necessary
            pipeline = GATENLPFactory.create(gateNlpModels.getModelsFilesystemPath(language).toFile());
        } catch (GateException | IOException | InterruptedException e) {
            LOGGER.error("failed building GateNLP Application", e);
            return false;
        }
        return true;
    }

    @Override
    public Annotations process(String content, String docId, Language language) {
        Annotations annotations = new Annotations(docId, getType(), language);
        try {
            // Gate annotated document
            String gateDocName = String.join(".", asList(Document.HASHER.hash(content), "txt"));
            GATENLPDocument gateDoc = new GATENLPDocument(gateDocName, content);

            // Tokenize input
            // NER input
            LOGGER.info("tokenizing ~ NAME-FINDING");
            pipeline.annotate(gateDoc);
            gateDoc.storeAnnotationSet();
            gateDoc.cleanDocument();

            // Feed annotation
            AnnotationSet tokenAnnotationSet = gateDoc.getAnnotationSet(GATE_STAGE_NAME.get(TOKEN));
            if (tokenAnnotationSet != null) {
                for (gate.Annotation gateAnnotation : new ArrayList<>(tokenAnnotationSet)) {
                    String word          = gateAnnotation.getFeatures().get("string").toString();
                    int tokenOffsetBegin = gateAnnotation.getStartNode().getOffset().intValue();
                    int tokenOffsetEnd   = gateAnnotation.getEndNode().getOffset().intValue();
                    annotations.add(TOKEN, tokenOffsetBegin, tokenOffsetEnd);
                }
            }

            // Feed annotation
            if (targetStages.contains(NER)) {
                BiFunction<GATENLPDocument, NamedEntity.Category, AnnotationSet> nerAnnotationSet =
                        (doc, category) -> doc.getAnnotationSet(GATE_NER_CATEGORY_NAME.get(category));
                targetEntities.stream()
                        .filter  ( category ->  nerAnnotationSet.apply(gateDoc, category) != null )
                        .forEach ( category -> {
                            for (gate.Annotation gateAnnotation : nerAnnotationSet.apply(gateDoc, category).inDocumentOrder()) {
                                String nerMention     = gateAnnotation.getFeatures().get("string").toString();
                                int    nerOffsetBegin = gateAnnotation.getStartNode().getOffset().intValue();
                                int    nerOffsetEnd   = gateAnnotation.getEndNode().getOffset().intValue();
                                annotations.add(NER, nerOffsetBegin, nerOffsetEnd, category);
                            }
                        });
            }
            return annotations;

        } catch (ResourceInstantiationException e) {
            LOGGER.error("Failed to createList Gate Document", e);
            return null;
        }
    }

    @Override
    public void terminate(Language language) throws InterruptedException {
        super.terminate(language);
        if ( ! caching) {
            pipeline.cleanApplication();
            pipeline = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (pipeline != null) {
            pipeline.cleanApplication();
            pipeline = null;
        }
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.empty();
    }

}
