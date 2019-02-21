package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import org.icij.datashare.IndexResource;
import org.icij.datashare.IndexWaiterFilter;
import org.icij.datashare.NamedEntityResource;
import org.icij.datashare.TaskResource;
import org.icij.datashare.session.LocalUserFilter;

import java.util.Map;
import java.util.Properties;

public class LocalMode extends CommonMode {
    public LocalMode(Properties properties) { super(properties);}
    public LocalMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(TaskResource.class).add(IndexResource.class).add(NamedEntityResource.class).filter(IndexWaiterFilter.class);
    }
}
