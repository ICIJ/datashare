package org.icij.datashare.com.bus.amqp;

public interface ConsumerCriteria {
    void newEvent();
    boolean isValid();
}
