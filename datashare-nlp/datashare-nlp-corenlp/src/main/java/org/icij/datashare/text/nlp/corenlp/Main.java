package org.icij.datashare.text.nlp.corenlp;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.icij.datashare.text.DatashareEventListener;
import org.icij.datashare.text.nlp.NLPDatashareEventListener;

public class Main {
    public static void main(String[] args) {
        Injector injector = Guice.createInjector(new ProdModule());
        DatashareEventListener listener = injector.getInstance(NLPDatashareEventListener.class);
        listener.waitForEvents();
    }
}
