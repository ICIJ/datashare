package org.icij.datashare.text.nlp.corenlp.models;

public class CoreNlpAnnotator<T> {
    public final T annotator;

    CoreNlpAnnotator(T annotator) {
            this.annotator = annotator;
        }
}
