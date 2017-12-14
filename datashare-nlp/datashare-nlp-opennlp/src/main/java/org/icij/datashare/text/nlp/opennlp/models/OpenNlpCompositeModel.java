package org.icij.datashare.text.nlp.opennlp.models;

import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;

import java.util.LinkedList;
import java.util.List;

public class OpenNlpCompositeModel implements ArtifactProvider {
    private final Language language;
    public final List<ArtifactProvider> models = new LinkedList<>();

    public OpenNlpCompositeModel(Language language) {
        this.language = language;
    }

    public void add(ArtifactProvider model) {
        models.add(model);
    }

    @Override
    public <T> T getArtifact(String s) {
        return null;
    }

    @Override
    public String getManifestProperty(String s) {
        return models.stream().map(m -> m.getManifestProperty(s)).
                reduce("", (s1, s2) -> s1 + "-" + s2);
    }

    @Override
    public String getLanguage() {
        return language.iso6391Code();
    }

    @Override
    public boolean isLoadedFromSerialized() {
        return models.stream().allMatch(ArtifactProvider::isLoadedFromSerialized);
    }
}
