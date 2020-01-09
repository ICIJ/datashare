package org.icij.datashare.com;

import java.util.function.Consumer;

public interface DataBus extends Publisher {
    int subscribe(Consumer<Message> subscriber, Channel... channels) throws InterruptedException;
    int subscribe(Consumer<Message> subscriber, Runnable subscriptionCallback, Channel... channels) throws InterruptedException;
    void unsubscribe(Consumer<Message> subscriber);
}
