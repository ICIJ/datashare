package org.icij.datashare.text.nlp.open;

import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.NlpStage;

public class OpenNlpNerComponent extends OpenNlpComponent {
    private final NamedEntity.Category category;

    public OpenNlpNerComponent(NamedEntity.Category category) {
        super(NlpStage.NER);
        this.category = category;
    }
}
