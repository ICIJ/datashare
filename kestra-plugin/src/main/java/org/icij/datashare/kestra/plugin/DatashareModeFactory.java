package org.icij.datashare.kestra.plugin;

import io.micronaut.context.annotation.Factory;
import io.micronaut.guice.annotation.Guice;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;


@Factory // Let's have micronaut call this factory at startup
@Guice( // We call the Micronaut/Guice binder
    modules = CommonMode.class,
    classes = {  // We list beans we want to retrieve
//        DatashareTaskFactory.class,
    }
)
public class DatashareModeFactory {
    public DatashareModeFactory() {
        System.out.println(">>> DummyGuiceFactory initialized!");
    }
}
