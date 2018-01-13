package org.icij.datashare;

import com.google.inject.AbstractModule;

public class ServiceModule extends AbstractModule{
    @Override
    protected void configure() {
        bind(IndexWrapper.class);
    }
}
