package org.icij.datashare.extension;

import org.icij.datashare.DynamicClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static java.util.Optional.ofNullable;

public class ExtensionLoader {
    public static final String CLASS_SUFFIX = ".class";
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
    public final Path extensionsDir;
    private File[] jars = null;

    public ExtensionLoader(Path extensionsDir) {
        this.extensionsDir = extensionsDir;
    }

    public synchronized void eagerLoadJars() throws FileNotFoundException {
        if (ClassLoader.getSystemClassLoader() instanceof DynamicClassLoader) {
            DynamicClassLoader classLoader = (DynamicClassLoader) ClassLoader.getSystemClassLoader();
            if (jars == null) {
                jars = getJars();
                loadJars(classLoader, jars);
            }
        } else {
            LOGGER.info("system class loader {} is not an instance of {} extension loading is disabled",
                    ClassLoader.getSystemClassLoader(), DynamicClassLoader.class);
        }
    }

    public synchronized <T> void load(Consumer<T> registerFunc, Predicate<Class<?>> predicate) throws FileNotFoundException {
        eagerLoadJars();
        if (jars != null) {
            for (File jar : jars) {
                registerClassesInJar(registerFunc, predicate, jar);
            }
        }
    }

    synchronized <T> Class<?> findClassesInJar(Predicate<Class<?>> predicate, final File jarFile) throws IOException {
        URLClassLoader ucl = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, getClass().getClassLoader());
        JarInputStream jarInputStream = new JarInputStream(new FileInputStream(jarFile));
        for (JarEntry jarEntry = jarInputStream.getNextJarEntry(); jarEntry != null; jarEntry = jarInputStream.getNextJarEntry()) {
            if (jarEntry.getName().endsWith(CLASS_SUFFIX)) {
                String classname = jarEntry.getName().replaceAll("/", "\\.");
                classname = classname.substring(0, classname.length() - CLASS_SUFFIX.length());
                if (!classname.contains("$") && !classname.startsWith("META-INF")) {
                    try {
                        final Class<?> myLoadedClass = Class.forName(classname, false, ucl);
                        if (predicate.test(myLoadedClass) &&
                                !myLoadedClass.isInterface() && !Modifier.isAbstract(myLoadedClass.getModifiers())) {
                            return myLoadedClass;
                        }
                    } catch (ClassNotFoundException | LinkageError e) {
                        LOGGER.warn("cannot load class " + classname, e);
                    }
                }
            }
        }
        return null;
    }

    private <T> void registerClassesInJar(Consumer<T> registerFunc, Predicate<Class<?>> predicate, File jar) {
        try {
            ofNullable(findClassesInJar(predicate, jar)).ifPresent(expectedClass -> registerFunc.accept((T) expectedClass));
        } catch (IOException e) {
            LOGGER.error("Cannot find class in jar " + jar, e);
        }
    }

    private void loadJars(DynamicClassLoader classLoader, File[] jars) {
        LOGGER.info("read directory {} and found jars (executable): {}", extensionsDir, this.jars);
        for (File jar : jars) {
            try {
                LOGGER.info("loading jar {}", jar);
                classLoader.add(jar.toURI().toURL());
            } catch (IOException e) {
                LOGGER.error("Cannot load jar " + jar, e);
            }
        }
    }

    File[] getJars() throws FileNotFoundException {
        return ofNullable(extensionsDir.toFile().listFiles(file -> file.toString().endsWith(".jar") && file.canExecute())).
                orElseThrow(() -> new FileNotFoundException("invalid path for extensions: " + extensionsDir));
    }
}
