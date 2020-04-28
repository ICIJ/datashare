package org.icij.datashare.extension;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.test.TestPipeline;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.*;
import java.io.*;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

public class PipelineRegistryTest {
    public @Rule TemporaryFolder folder = new TemporaryFolder();
    PipelineRegistry pipelineRegistry;

    @Test
    public void test_init_pipeline_registry_no_extension() throws FileNotFoundException {
        pipelineRegistry.load();
        assertThat(pipelineRegistry.getPipelineTypes()).isEmpty();
    }

    @Test
    public void test_load_pipeline_registry_one_extension() throws IOException {
        createJar(folder.getRoot().toPath(), "plugin", new File("src/test/java/org/icij/datashare/text/nlp/test/TestPipeline.java"));
        pipelineRegistry.load();
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.TEST);
    }

    @Test
    public void test_load_pipeline_registry_one_extension_with_interface() throws IOException {
        createJar(folder.getRoot().toPath(), "plugin", new File("src/test/java/org/icij/datashare/text/nlp/test/TestPipeline.java"),
                new File("src/main/java/org/icij/datashare/text/nlp/AbstractPipeline.java"));
        pipelineRegistry.load();
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.TEST);
    }

    @Test
    public void test_load_pipeline_registry_one_extension_with_unknown_class_from_classpath() throws IOException {
        createJar(folder.getRoot().toPath(), "plugin", PLUGIN_PIPELINE_SOURCE);
        pipelineRegistry.load();
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
    public void test_plugin_dir_not_found() throws Exception {
        new PipelineRegistry(new PropertiesProvider(new HashMap<>())).load();
    }

    private void createJar(Path pathToJar, String jarName, File... javaSources) throws IOException {
        Path jarRoot = pathToJar.resolve("jar");
        jarRoot.toFile().mkdirs();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(new DiagnosticCollector<>(), null, null);
        Iterable<? extends JavaFileObject> compUnits = fileManager.getJavaFileObjects(javaSources);
        compiler.getTask(null, fileManager, null, asList("-d", jarRoot.toString()), null, compUnits).call();

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        JarOutputStream target = new JarOutputStream(new FileOutputStream(pathToJar.resolve(jarName + ".jar").toString()), manifest);

        try(BufferedOutputStream bos = new BufferedOutputStream(target)) {
            Files.walkFileTree(jarRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path classFilePath, BasicFileAttributes basicFileAttributes) throws IOException {
                    BufferedInputStream br = new BufferedInputStream(new FileInputStream(classFilePath.toFile()));
                    target.putNextEntry(new JarEntry(jarRoot.relativize(classFilePath).toString()));
                    int c;
                    byte[] buffer = new byte[1024];
                    while ((c = br.read(buffer)) != -1) {
                        bos.write(buffer, 0, c);
                    }
                    br.close();
                    bos.flush();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private void createJar(Path pathToJar, String jarName, String... javaSources) throws IOException {
        Path srcRoot = pathToJar.resolve("src");
        srcRoot.toFile().mkdirs();
        Pattern pattern = Pattern.compile(".*package ([a-zA-Z.]*);.*class ([a-zA-Z]*).*", Pattern.DOTALL);
        List<File> files = new LinkedList<>();
        for (String javaSource : javaSources) {
            Matcher matcher = pattern.matcher(javaSource);
            if (matcher.matches()) {
                String packageName = matcher.group(1);
                String className = matcher.group(2);
                String path = packageName.replaceAll("\\.", "/");
                Path pathToSource = srcRoot.resolve(path);
                pathToSource.toFile().mkdirs();
                Path sourcePath = pathToSource.resolve(className + ".java");
                Files.write(sourcePath, asList(javaSource.split("\n")));
                files.add(sourcePath.toFile());
            }
        }
        createJar(pathToJar, jarName, files.toArray(new File[]{}));
    }

    @Before
    public void setUp() {
        pipelineRegistry = new PipelineRegistry(new PropertiesProvider(new HashMap<String, String>() {{
            put(PropertiesProvider.PLUGINS_DIR, folder.getRoot().getPath());
        }}));
    }

    String PLUGIN_PIPELINE_SOURCE = "package org.icij.datashare.text.nlp.test;\n" +
            "\n" +
            "import org.icij.datashare.PropertiesProvider;\n" +
            "import org.icij.datashare.text.Language;\n" +
            "import org.icij.datashare.text.NamedEntity;\n" +
            "import org.icij.datashare.text.nlp.Annotations;\n" +
            "import org.icij.datashare.text.nlp.NlpStage;\n" +
            "import org.icij.datashare.text.nlp.Pipeline;\n" +
            "\n" +
            "import java.nio.charset.Charset;\n" +
            "import java.util.List;\n" +
            "import java.util.Optional;\n" +
            "\n" +
            "public class PluginPipeline implements Pipeline {\n" +
            "    @Override\n" +
            "    public Type getType() {\n" +
            "        return Type.TEST;\n" +
            "    }\n" +
            "\n" +
            "    public PluginPipeline(PropertiesProvider provider) {}\n" +
            "    @Override\n" +
            "    public boolean initialize(Language language) throws InterruptedException {\n" +
            "        return false;\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public Annotations process(String content, String docId, Language language) throws InterruptedException {\n" +
            "        return null;\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public void terminate(Language language) throws InterruptedException {\n" +
            "\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public boolean supports(NlpStage stage, Language language) {\n" +
            "        return false;\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public List<NamedEntity.Category> getTargetEntities() {\n" +
            "        return null;\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public List<NlpStage> getStages() {\n" +
            "        return null;\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public boolean isCaching() {\n" +
            "        return false;\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public Charset getEncoding() {\n" +
            "        return null;\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public Optional<String> getPosTagSet(Language language) {\n" +
            "        return Optional.empty();\n" +
            "    }\n" +
            "}\n";
}
