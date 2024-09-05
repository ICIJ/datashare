package org.icij.datashare.extension;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.test.TestPipeline;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.JarUtil.createJar;

public class PipelineRegistryTest {
    public @Rule TemporaryFolder folder = new TemporaryFolder();
    PipelineRegistry pipelineRegistry;
    ExtensionLoader loader;

    @Test
    public void test_init_pipeline_registry_no_extension() throws FileNotFoundException {
        pipelineRegistry.load(loader);
        assertThat(pipelineRegistry.getPipelineTypes()).isEmpty();
    }

    @Test
    public void test_load_pipeline_registry_one_extension() throws IOException {
        createJar(folder.getRoot().toPath(), "extension", new File("src/test/java/org/icij/datashare/text/nlp/test/TestPipeline.java"));
        pipelineRegistry.load(loader);
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.TEST);
    }

    @Test
    public void test_load_pipeline_registry_one_extension_with_interface() throws IOException {
        createJar(folder.getRoot().toPath(), "extension", new File("src/test/java/org/icij/datashare/text/nlp/test/TestPipeline.java"),
                new File("src/main/java/org/icij/datashare/text/nlp/AbstractPipeline.java"));
        pipelineRegistry.load(loader);
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.TEST);
    }

    @Test
    public void test_load_pipeline_registry_one_extension_with_unknown_class_from_classpath() throws IOException {
        createJar(folder.getRoot().toPath(), "extension", EXTENSION_PIPELINE_SOURCE);
        pipelineRegistry.load(loader);
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.TEST);
    }

    @Test
    public void test_register_pipeline_from_class() {
        pipelineRegistry.register(TestPipeline.class);
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.TEST);
    }

    @Test
    public void test_register_pipeline_from_type() {
        pipelineRegistry.register(Pipeline.Type.TEST);
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.TEST);
    }

    @Test(expected = FileNotFoundException.class)
    public void test_extension_dir_not_found() throws Exception {
        new PipelineRegistry(new PropertiesProvider(new HashMap<>())).load(new ExtensionLoader(Paths.get("./unknown")));
    }

    @Before
    public void setUp() {
        pipelineRegistry = new PipelineRegistry(new PropertiesProvider());
        loader = new ExtensionLoader(folder.getRoot().toPath());
    }

    String EXTENSION_PIPELINE_SOURCE = """
        package org.icij.datashare.text.nlp.test;

        import org.icij.datashare.PropertiesProvider;
        import org.icij.datashare.text.Document;
        import org.icij.datashare.text.Language;
        import org.icij.datashare.text.NamedEntity;
        import org.icij.datashare.text.nlp.Annotations;
        import org.icij.datashare.text.nlp.Pipeline;
        import org.icij.datashare.text.nlp.NlpTag;

        import java.nio.charset.Charset;
        import java.util.List;
        import java.util.stream.Stream;
        import java.util.Optional;

        public class ExtensionPipeline implements Pipeline {
            @Override
            public Type getType() {
                return Type.TEST;
            }

            public ExtensionPipeline(PropertiesProvider provider) {}
            @Override
            public boolean initialize(Language language) throws InterruptedException {
                return false;
            }
            @Override
            public List<List<NlpTag>> processText(Stream<String> batch, Language language)  {
                return null;
            }

            @Override
            public void terminate(Language language) throws InterruptedException {

            }

            @Override
            public boolean supports(Language language) {
                return false;
            }

            @Override
            public List<NamedEntity.Category> getTargetEntities() {
                return null;
            }

            @Override
            public boolean isCaching() {
                return false;
            }

            @Override
            public Charset getEncoding() {
                return null;
            }
        }
        """;
}
