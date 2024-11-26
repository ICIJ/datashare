package org.icij.datashare;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class PipelineRegistry extends org.icij.datashare.extension.PipelineRegistry {
    @Inject
    public PipelineRegistry(PropertiesProvider propertiesProvider) {
        super(propertiesProvider);
    }
}
