package org.icij.datashare.batch;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.icij.datashare.text.indexing.Indexer;

public class App {
    public static void main(String[] args) throws Exception {
        Injector injector = Guice.createInjector(new AppInjector());

		injector.getInstance(BatchSearchRunner.class).call();
        injector.getInstance(Indexer.class).close();
    }
}
