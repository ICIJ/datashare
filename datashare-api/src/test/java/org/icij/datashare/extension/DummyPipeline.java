package org.icij.datashare.extension;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;

public class DummyPipeline implements Pipeline {
    @Override
    public Type getType() {
        return null;
    }

    @Override
    public boolean initialize(Language language) throws InterruptedException {
        return false;
    }

    @Override
    public Annotations process(String content, String docId, Language language) throws InterruptedException {
        return null;
    }

    @Override
    public void terminate(Language language) throws InterruptedException {

    }

    @Override
    public boolean supports(NlpStage stage, Language language) {
        return false;
    }

    @Override
    public List<NamedEntity.Category> getTargetEntities() {
        return null;
    }

    @Override
    public List<NlpStage> getStages() {
        return null;
    }

    @Override
    public boolean isCaching() {
        return false;
    }

    @Override
    public Charset getEncoding() {
        return null;
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.empty();
    }
}
