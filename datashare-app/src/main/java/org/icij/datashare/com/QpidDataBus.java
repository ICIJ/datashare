package org.icij.datashare.com;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

public class QpidDataBus implements Publisher, DataBus, Closeable {
    @Override
    public void close() throws IOException {

    }

    @Override
    public int subscribe(Consumer<Message> consumer, Channel... channels) throws InterruptedException {
        return 0;
    }

    @Override
    public int subscribe(Consumer<Message> consumer, Runnable runnable, Channel... channels) throws InterruptedException {
        return 0;
    }

    @Override
    public void unsubscribe(Consumer<Message> consumer) {

    }

    @Override
    public boolean getHealth() {
        return false;
    }

    @Override
    public void publish(Channel channel, Message message) {

    }
}
