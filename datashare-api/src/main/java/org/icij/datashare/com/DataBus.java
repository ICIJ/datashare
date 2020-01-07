package org.icij.datashare.com;

import java.util.function.Consumer;

public interface DataBus {
    void subscribe(Consumer<Message> subscriber, Channel... channels);
    void subscribe(Consumer<Message> subscriber, Runnable subscriptionCallback, Channel... channels);
    void unsubscribe(Consumer<Message> subscriber);
}
