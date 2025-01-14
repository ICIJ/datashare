package org.icij.datashare.com.queue;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;

import java.io.IOException;
import java.net.URISyntaxException;

@Singleton
public class AmqpInterlocutor extends org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor {
    @Inject
    public AmqpInterlocutor(PropertiesProvider propertiesProvider) throws IOException, URISyntaxException {
        super(propertiesProvider);
    }
}
