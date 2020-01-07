package org.icij.datashare.com;

@FunctionalInterface
public interface Publisher {
    void publish(Channel channel, Message message);
}
