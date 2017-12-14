package org.icij.datashare.text.nlp.ixapipe;

import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;


abstract class IxaModels<T> extends AbstractModels<IxaAnnotate<T>> {
    private final String VERSION = "1.5";

    IxaModels(NlpStage stage) { super(Pipeline.Type.IXAPIPE, stage);}

    @Override
    protected String getVersion() { return VERSION;}
}
