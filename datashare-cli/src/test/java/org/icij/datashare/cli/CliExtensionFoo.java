package org.icij.datashare.cli;


import static java.util.Collections.singletonList;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Module;
import java.util.Properties;
import java.util.function.Function;
import joptsimple.OptionParser;
import org.icij.datashare.cli.spi.CliExtension;

public class CliExtensionFoo implements CliExtension {
    protected FooService service;

    @Override
    public void init(Function<Module[], Injector> injectorCreator)  {
        Injector injector = injectorCreator.apply(new Module[] {new TestModule()});
        service = injector.getInstance(FooService.class);
    }


    @Override
    public void addOptions(OptionParser parser) {
        parser.acceptsAll(singletonList("foo"), "Test foo")
            .withRequiredArg()
            .ofType(String.class);
        parser.acceptsAll(singletonList("fooCommand"));
    }

    @Override
    public String identifier() {
        return "foo";
    }

    @Override
    public void run(Properties properties) throws Exception {
        this.service.foo(properties);
    }

    static class TestModule extends AbstractModule {
        @Override
        public void configure() {
            bind(FooService.class).to(FooService.FooServiceImpl.class).asEagerSingleton();
        }
    }
}