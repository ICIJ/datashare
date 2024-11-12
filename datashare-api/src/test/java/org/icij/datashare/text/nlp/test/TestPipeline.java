package org.icij.datashare.text.nlp.test;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;

import java.nio.charset.Charset;
import java.util.List;

public class TestPipeline implements Pipeline {
    @Override
    public Type getType() {
        return Type.TEST;
    }

    public TestPipeline(PropertiesProvider ignore) {}
    @Override
    public boolean initialize(Language language) {
        return false;
    }

    @Override
    public List<NamedEntity> process(Document doc) {
        return null;
    }

    @Override
    public List<NamedEntity> process(Document doc, int contentLength, int contentOffset) {
        return null;
    }

    @Override
    public void terminate(Language language) {
    }

    @Override
    public boolean supports(Language language) {
        return false;
    }

    @Override
    public List<NamedEntity.Category> getTargetEntities() {
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
}
