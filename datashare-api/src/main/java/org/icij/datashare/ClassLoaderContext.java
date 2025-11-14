package org.icij.datashare;

import java.util.function.Supplier;

public class ClassLoaderContext implements AutoCloseable {
    private final ClassLoader originalLoader;
    public final DynamicClassLoader dynamicLoader;

    public ClassLoaderContext(Supplier<DynamicClassLoader> classLoaderSupplier) {
        originalLoader = Thread.currentThread().getContextClassLoader();
        dynamicLoader = classLoaderSupplier.get();
        Thread.currentThread().setContextClassLoader(dynamicLoader);
    }

    @Override
    public void close() {
        Thread.currentThread().setContextClassLoader(originalLoader);
    }
}
