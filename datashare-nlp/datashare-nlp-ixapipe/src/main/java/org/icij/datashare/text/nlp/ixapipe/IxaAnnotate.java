package org.icij.datashare.text.nlp.ixapipe;

public class IxaAnnotate<T> {
    public final T annotate;

    IxaAnnotate(T annotate) {
        this.annotate = annotate;
    }
}
