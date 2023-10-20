package org.icij.datashare.cli;

import static org.fest.assertions.Assertions.assertThat;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public class CliExtensionServiceTest {
    interface InjectedDependency {
         Properties getProperties();
    }

    public static class InjectedDependencyImpl implements InjectedDependency {
        private final Properties properties;

        @Inject
        public InjectedDependencyImpl(Properties properties) {
            this.properties = properties;
        }

        @Override
        public Properties getProperties() {
            return this.properties;
        }
    }

    public static class TestModule extends AbstractModule {
        @Override
        public void configure() {
            bind(Properties.class).toInstance(new Properties());
            bind(InjectedDependency.class).to(InjectedDependencyImpl.class).asEagerSingleton();
        }
    }

    @Test
    public void test_load_extensions() {
        // Given
        Injector injector = Guice.createInjector(new TestModule());
        // When
        List<Cli.CliExtender> factories = CliExtensionService.getInstance().getExtensions();
        // Then
        assertThat(factories).hasSize(1);
        assertThat(factories.get(0)).isInstanceOf(CliExtensionFoo.class);
    }

    @Test
    public void test_load_runners() {
        // Given
        Injector injector = Guice.createInjector(new TestModule());
        // When
        List<Cli.CliRunner> runners = CliExtensionService.getInstance().getExtensionRunners(injector);
        // Then
        assertThat(runners).hasSize(1);
        Cli.CliRunner firstRunner = runners.get(0);
        assertThat(firstRunner).isInstanceOf(CliExtensionFoo.class);
        assertThat(((CliExtensionFoo)firstRunner).fooProperties).isNotNull();
    }
}
