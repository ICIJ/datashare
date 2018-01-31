package org.icij.datashare.text.nlp.open;

import org.icij.datashare.text.nlp.NlpStage;

import java.util.concurrent.locks.ReentrantLock;

public class OpenNlpComponent {
    public final NlpStage nlpStage;
    private boolean loaded = false;
    private final ReentrantLock lock = new ReentrantLock();

    public OpenNlpComponent(NlpStage nlpStage) {
        this.nlpStage = nlpStage;
    }

    boolean load() {
        // loading resource with lock
        return false;
    }

    public boolean isLoaded() { return loaded;}
}
