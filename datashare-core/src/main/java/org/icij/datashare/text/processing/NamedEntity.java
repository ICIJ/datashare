package org.icij.datashare.text.processing;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static java.util.logging.Level.SEVERE;

import me.xuender.unidecode.Unidecode;

import org.icij.datashare.text.Language;

import org.icij.datashare.text.hashing.Hasher;
import static org.icij.datashare.text.hashing.Hasher.SHA_384;

import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType;


/**
 * Created by julien on 5/12/16.
 */
public class NamedEntity {

    private static final Logger LOGGER = Logger.getLogger(NamedEntity.class.getName());

    private static final Hasher HASHER = SHA_384;


    public static Optional<NamedEntity> create(NamedEntityCategory cat, String mention)  {
        if (cat == null || mention.isEmpty()) {
            LOGGER.log(SEVERE, "Undefined parameter(s): category " + cat + ", mention " + mention);
            return Optional.empty();
        }
        return Optional.of(new NamedEntity(cat, mention));
    }

    public static Optional<NamedEntity> create(NamedEntityCategory cat, String mention, int offset) {
        Optional<NamedEntity> optEntity = create(cat, mention);
        if ( ! optEntity.isPresent())
            return Optional.empty();
        optEntity.get().setOffset(offset);
        return optEntity;
    }

    public static Optional<NamedEntity> create(NamedEntityCategory cat, String mention, int offset, String doc) {
        Optional<NamedEntity> optEntity = create(cat, mention, offset);
        if ( ! optEntity.isPresent())
            return Optional.empty();
        optEntity.get().setDocument(doc);
        return optEntity;
    }

    public static Optional<NamedEntity> create(NamedEntityCategory cat, String mention, int offset, String doc, NLPPipelineType extr) {
        Optional<NamedEntity> optEntity = create(cat, mention, offset, doc);
        if ( ! optEntity.isPresent())
            return Optional.empty();
        optEntity.get().setNlpPipeline(extr);
        return optEntity;
    }


    private NamedEntity(NamedEntityCategory cat, String men) {
        category = cat;
        mention = men;
        hash = HASHER.hash(getMentionNormalForm());
    }


    // Actual string denoting the named entity
    private final String mention;

    // Mention's hash
    private final String hash;

    // Category (Pers, Org, Loc)
    private final NamedEntityCategory category;

    // Approximative offset in document (lower bound ong number of chars from beginning)
    private int offset = -1;

    // Parts-of-speech associated with mention
    private String partOfSpeech;

    // Tokens appearing just before
    private List<String> leftContext;

    // Tokens appearing just after
    private List<String> rightContext;

    // Document uid (content hash) from which extracted
    private String document;

    // Document path (local) from which extracted
    private Path documentPath;

    // Type of pipeline which extracted
    private NLPPipelineType nlpPipeline;

    // Language setting by pipeline at extraction
    private Language nlpPipelineLanguage;


    public String getMention() { return mention; }

    public String getHash() { return hash; }

    public NamedEntityCategory getCategory() { return category; }


    public Optional<String> getDocument() { return Optional.ofNullable(document); }

    public void setDocument(String doc) { document = doc; }


    public Optional<Path> getDocumentPath() { return Optional.ofNullable(documentPath); }

    public void setDocumentPath(Path docPath) { documentPath = docPath; }


    public OptionalInt getOffset() {
        if (offset < 0)
            return OptionalInt.empty();
        return OptionalInt.of(offset);
    }

    public void setOffset(int off) {
        if (off >= 0)
            offset = off;
    }


    public Optional<String> getPartOfSpeech() { return Optional.ofNullable(partOfSpeech); }

    public void setPartOfSpeech(String pos) { partOfSpeech = pos; }


    public Optional<NLPPipelineType> getNlpPipeline() { return Optional.ofNullable(nlpPipeline); }

    public void setNlpPipeline(NLPPipelineType pipeline) { nlpPipeline = pipeline; }


    public Optional<Language> getNlpPipelineLanguage() { return Optional.ofNullable(nlpPipelineLanguage); }

    public void setNlpPipelineLanguage(Language extrLang) {nlpPipelineLanguage = extrLang; }


    public Optional<List<String>> getRightContext() { return Optional.ofNullable(rightContext); }

    public void setRightContext(List<String> right) { rightContext = right; }


    public Optional<List<String>> getLeftContext() {
        return Optional.ofNullable(leftContext);
    }

    public void setLeftContext(List<String> left) {
        leftContext = left;
    }


    @Override
    public String toString() {
        List<String> features = new ArrayList<>();

        features.add(getMention());

        features.add(getCategory().toString());

        OptionalInt off = getOffset();
        if (off.isPresent())
            features.add(String.valueOf(off.getAsInt()));
        else
            features.add("NONE");

        Optional<NLPPipelineType> extr = getNlpPipeline();
        if (extr.isPresent())
            features.add(extr.get().toString());
        else
            features.add("NONE");

        Optional<Language> extrLang = getNlpPipelineLanguage();
        if (extrLang.isPresent())
            features.add(extrLang.get().toString().toUpperCase(Locale.ROOT));
        else
            features.add("NONE");

        Optional<String> pos = getPartOfSpeech();
        if (pos.isPresent())
            features.add(pos.get().toUpperCase(Locale.ROOT));
        else
            features.add("NONE");

        features.add(getMentionNormalForm());

        features.add(getHash());

        Optional<Path> p = getDocumentPath();
        if (p.isPresent())
            features.add(p.get().toString());
        else
            features.add("NONE");

        Optional<String> docHash = getDocument();
        if (docHash.isPresent())
            features.add(docHash.get());
        else
            features.add("NONE");

        return String.join(";", features);
    }

    /**
     *  Mention's normal form (from which hash is computed)
     *  Transliteration of Unicode to Ascii; trimming; to lower case.
     *
     * @return
     */
    public String getMentionNormalForm() {
        return Unidecode
                .decode(mention)
                .trim()
                .replaceAll("(\\s+)", " ")
                .toLowerCase();
    }

}
