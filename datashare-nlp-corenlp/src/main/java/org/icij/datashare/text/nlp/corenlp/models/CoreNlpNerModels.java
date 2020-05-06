package org.icij.datashare.text.nlp.corenlp.models;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.ling.CoreLabel;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.NER;


/**
 * Stanford CoreNLP Part-of-Speech taggers
 * <p>
 * Tagsets
 * <p>
 * ENGLISH: PENN TREEBANK
 * https://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
 * http://www.cs.upc.edu/~nlp/SVMTool/PennTreebank.html
 * <p>
 * FRENCH CC:
 * http://french-postaggers.tiddlyspot.com/
 * <p>
 * SPANISH: (reduced) ANCORA
 * http://nlp.stanford.edu/software/spanish-faq.shtml#tagset
 * https://web.archive.org/web/20160325024315/http://nlp.lsi.upc.edu/freeling/doc/tagsets/tagset-es.html
 * http://stackoverflow.com/questions/27047450/meaning-of-stanford-spanish-pos-tagger-tags
 * <p>
 * GERMAN: STTS
 * http://www.ims.uni-stuttgart.de/forschung/ressourcen/lexika/TagSets/stts-table.html
 * <p>
 * Created by julien on 8/31/16.
 */
public class CoreNlpNerModels extends CoreNlpModels<AbstractSequenceClassifier<CoreLabel>> {
    private static volatile CoreNlpNerModels instance;
    private static final Object mutex = new Object();

    public static final Map<Language, String> POS_TAGSET = new HashMap<Language, String>() {{
        put(ENGLISH, "PENN TREEBANK");
        put(SPANISH, "ANCORA");
        put(FRENCH, "CC");
        put(GERMAN, "STTS");
    }};

    public static CoreNlpNerModels getInstance() {
        CoreNlpNerModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new CoreNlpNerModels();
                }
            }
        }
        return instance;
    }

    @Override
    protected CoreNlpAnnotator<AbstractSequenceClassifier<CoreLabel>> loadModelFile(Language language, ClassLoader loader) throws IOException {
        Path modelFilePath = getModelsBasePath(language).resolve(getJarFileName(language));
        if (language != ENGLISH) {
            try {
                get(ENGLISH); // english models needs to be loaded and added to classpath for all languages
            } catch (InterruptedException e) {
                throw new IllegalStateException("cannot load english models ", e);
            }
        }
        super.addResourceToContextClassLoader(modelFilePath, loader);
        try {
            return new CoreNlpAnnotator<>(CRFClassifier.getClassifier(getInJarModelPath(language)));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("cannot find CRFClassifier class", e);
        }
    }

    private CoreNlpNerModels() {
        super(NER);
        modelNames.put(ENGLISH, "ner/english.all.3class.distsim.crf.ser.gz");
        modelNames.put(SPANISH, "ner/spanish.ancora.distsim.s512.crf.ser.gz");
        modelNames.put(FRENCH, "ner/english.all.3class.distsim.crf.ser.gz");
        modelNames.put(CHINESE, "ner/chinese.misc.distsim.crf.ser.gz");
        modelNames.put(GERMAN, "ner/german.conll.germeval2014.hgc_175m_600.crf.ser.gz");
    }
    @Override
    String getPropertyName() { return "ner.model";}
}
