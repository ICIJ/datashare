package org.icij.datashare.text.processing;

import me.xuender.unidecode.Unidecode;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static java.util.logging.Level.SEVERE;
import static org.icij.datashare.text.hashing.Hasher.SHA_512;

import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType;


/**
 * Created by julien on 5/12/16.
 */
public class NamedEntity {

    private static final Logger LOGGER = Logger.getLogger(NamedEntity.class.getName());


    public static Optional<NamedEntity> create(NamedEntityCategory cat, String mention)  {
        if (cat == null || mention.isEmpty()) {
            LOGGER.log(SEVERE, "Failed to create NamedEntity; " +
                    "undefined category " + cat +
                    ", mention " + mention);
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
        optEntity.get().setExtractor(extr);
        return optEntity;
    }


    private NamedEntity(NamedEntityCategory cat, String men) {
        category = cat;
        mention = men;
        hash = SHA_512.hash(getMentionNormalForm());
    }


    // Actual String denoting entity
    private String mention;

    // Mention's hash
    private String hash;

    // Category (Pers, Org, Loc)
    private NamedEntityCategory category;

    // Approximative offset (chars from beginning) in document
    private int offset = -1;

    // Tokens appearing just before
    private List<String> leftContext;

    // Tokens appearing just after
    private List<String> rightContext;

    // Document uid (content hash) from which extracted
    private String document;

    // Document path (local) from which extracted
    private Path documentPath;

    // Type of pipeline which extracted
    private NLPPipelineType extractor;


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


    public Optional<NLPPipelineType> getExtractor() { return Optional.ofNullable(extractor); }

    public void setExtractor(NLPPipelineType extr) { extractor = extr; }


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
            features.add("-1");

        Optional<Path> p = getDocumentPath();
        if (p.isPresent())
            features.add(p.get().toString());
        else
            features.add("none");

        Optional<NLPPipelineType> extr = getExtractor();
        if (extr.isPresent())
            features.add(extr.get().toString());
        else
            features.add("none");

        features.add(getMentionNormalForm());

        features.add(getHash());

        Optional<String> docHash = getDocument();
        if (docHash.isPresent())
            features.add(docHash.get());
        else
            features.add("none");

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
