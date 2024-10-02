package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;

@Singleton
public class TaskSupplierAmqp extends org.icij.datashare.asynctasks.TaskSupplierAmqp {
    // Convenience class made to ease injection and test
    @Inject
    public TaskSupplierAmqp(AmqpInterlocutor amqp, PropertiesProvider propertiesProvider) throws IOException {
        super(amqp, Utils.getRoutingKey(propertiesProvider));
    }
}
