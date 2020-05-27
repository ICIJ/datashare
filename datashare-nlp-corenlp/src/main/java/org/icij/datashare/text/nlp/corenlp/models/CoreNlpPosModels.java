package org.icij.datashare.text.nlp.corenlp.models;

import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import org.icij.datashare.text.Language;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.POS;


/**
 * Stanford CoreNLP Part-of-Speech taggers
 *
 * Tagsets
 *
 * ENGLISH: PENN TREEBANK
 * https://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
 * http://www.cs.upc.edu/~nlp/SVMTool/PennTreebank.html
 *
 * FRENCH CC:
 * http://french-postaggers.tiddlyspot.com/
 *
 * SPANISH: (reduced) ANCORA
 * http://nlp.stanford.edu/software/spanish-faq.shtml#tagset
 * https://web.archive.org/web/20160325024315/http://nlp.lsi.upc.edu/freeling/doc/tagsets/tagset-es.html
 * http://stackoverflow.com/questions/27047450/meaning-of-stanford-spanish-pos-tagger-tags
 *
 * GERMAN: STTS
 * http://www.ims.uni-stuttgart.de/forschung/ressourcen/lexika/TagSets/stts-table.html
 *
 * Created by julien on 8/31/16.
 */
public class CoreNlpPosModels extends CoreNlpModels<MaxentTagger> {
    private static volatile CoreNlpPosModels instance;
    private static final Object mutex = new Object();

    public static final Map<Language, String> POS_TAGSET = new HashMap<Language, String>() {{
        put(ENGLISH, "PENN TREEBANK");
        put(SPANISH, "ANCORA");
        put(FRENCH,  "CC");
        put(GERMAN,  "STTS");
    }};

    public static CoreNlpPosModels getInstance() {
        CoreNlpPosModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new CoreNlpPosModels();
                }
            }
        }
        return instance;
    }

    @Override
    protected CoreNlpAnnotator<MaxentTagger> loadModelFile(Language language) {
        Path modelFilePath = getModelsBasePath(language).resolve(getJarFileName(language));
        super.addResourceToContextClassLoader(modelFilePath);
        return new CoreNlpAnnotator<>(new MaxentTagger(getInJarModelPath(language)));
    }

    private CoreNlpPosModels() {
        super(POS);
        modelNames.put(ENGLISH, "pos-tagger/english-left3words/english-left3words-distsim.tagger");
        modelNames.put(SPANISH, "pos-tagger/spanish/spanish-distsim.tagger");
        modelNames.put(FRENCH, "pos-tagger/french/french.tagger");
        modelNames.put(GERMAN, "pos-tagger/german/german-hgc.tagger");
    }
    @Override
    String getPropertyName() { return "pos.model";}
}

