package org.icij.datashare.text.nlp;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class NlpApp {
    public static void main(String[] args) {
        Injector injector = Guice.createInjector(new ProdModule());
        DatashareListener listener = injector.getInstance(NlpDatashareListener.class);
        listener.waitForEvents();
    }
}
