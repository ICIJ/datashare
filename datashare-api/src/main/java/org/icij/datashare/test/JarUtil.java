package org.icij.datashare.test;

import javax.tools.*;
import java.io.*;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;

public class JarUtil {
    public static void createJar(Path pathToJar, String jarName, File... javaSources) throws IOException {
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
         pathToJar.resolve(jarName + ".jar").toFile().setExecutable(true);
     }

     public static void createJar(Path pathToJar, String jarName, String... javaSources) throws IOException {
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
}
