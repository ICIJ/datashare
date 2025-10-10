package org.icij.datashare.kestra.plugin;

import io.micronaut.context.annotation.Factory;
import io.micronaut.guice.annotation.Guice;


@Factory // Let's have micronaut call this factory at startup
@Guice( // We call the Micronaut/Guice binder
    modules = DummyModule.class,
    classes = {  // We list beans we want to retrieve
//        DummyModule.DummyImpl.class,
    }
)
public class DummyGuiceFactory {
    public DummyGuiceFactory() {
        System.out.println(">>> DummyGuiceFactory initialized!");
    }
}
