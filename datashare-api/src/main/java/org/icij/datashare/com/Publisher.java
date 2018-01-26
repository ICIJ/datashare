package org.icij.datashare.com;

public interface Publisher {
    void publish(Channel channel, Message message);
}
