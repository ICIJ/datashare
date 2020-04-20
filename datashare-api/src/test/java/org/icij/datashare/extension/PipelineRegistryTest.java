package org.icij.datashare.extension;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

public class PipelineRegistryTest {
    public @Rule TemporaryFolder folder = new TemporaryFolder();
    PipelineRegistry pipelineRegistry;

    @Test
    public void test_init_pipeline_registry_no_extension() {
        pipelineRegistry.load();
        assertThat(pipelineRegistry.getPipelineTypes()).isEmpty();
    }

    @Test
    public void test_load_pipeline_registry_one_extension() throws IOException {
        createJar(folder.getRoot().toPath(), "plugin", new File("src/main/java/org/icij/datashare/text/nlp/email/EmailPipeline.java"));
        pipelineRegistry.load();
        assertThat(pipelineRegistry.getPipelineTypes()).contains(Pipeline.Type.EMAIL);
    }

    private void createJar(Path pathToJar, String jarName, File... javaSources) throws IOException {
        Path jarRoot = pathToJar.resolve("jar");
        jarRoot.toFile().mkdirs();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        for (File javaSource : javaSources) {
            Iterable<? extends JavaFileObject> compUnits = fileManager.getJavaFileObjects(javaSource);
            compiler.getTask(null, fileManager, null, asList("-d", jarRoot.toString()), null, compUnits).call();
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        JarOutputStream target = new JarOutputStream(new FileOutputStream(pathToJar.resolve(jarName + ".jar").toString()), manifest);

        try(BufferedOutputStream bos = new BufferedOutputStream(target)) {
            Files.walkFileTree(jarRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path classFilePath, BasicFileAttributes basicFileAttributes) throws IOException {
                    BufferedReader br = new BufferedReader(new FileReader(classFilePath.toFile()));
                    target.putNextEntry(new JarEntry(jarRoot.relativize(classFilePath).toString()));
                    int c;
                    while ((c = br.read()) != -1) {
                        bos.write(c);
                    }
                    br.close();
                    bos.flush();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Before
    public void setUp() {
        pipelineRegistry = new PipelineRegistry(new PropertiesProvider(new HashMap<String, String>() {{
            put(PropertiesProvider.PLUGINS_DIR, folder.getRoot().getPath());
        }}));
    }
}
