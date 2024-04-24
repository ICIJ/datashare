package org.icij.datashare.asynctasks.bus.amqp;

public interface ConsumerCriteria {
    void newEvent();
    boolean isValid();
}
