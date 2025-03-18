package org.icij.datashare.asynctasks.bus.amqp;

import com.rabbitmq.client.AlreadyClosedException;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * Class for consumer implementation. Is orchestrates the received events handling : deserialize and save.
 *
 * @param <Evt>         The event class that are going to be consumed by the consumer
 * @param <EvtConsumer> The class for handling the received event class
 */
public class AmqpConsumer<Evt extends Event, EvtConsumer extends Consumer<Evt>> implements Deserializer<Evt> {
    private static final int WAIT_CLOSED_CHANNEL_DELAY_MS = 1000;
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    protected final AmqpInterlocutor amqpInterlocutor;
    public final EvtConsumer eventConsumer;
    private final AmqpChannel channel;
    private final AtomicReference<String> consumerTag = new AtomicReference<>();
    private final Class<Evt> evtClass;

    public AmqpConsumer(AmqpInterlocutor amqpInterlocutor,
                        EvtConsumer eventConsumer, AmqpQueue queue, Class<Evt> evtClass) throws IOException {
        this(amqpInterlocutor, eventConsumer, queue, evtClass, null, null);
    }

    public AmqpConsumer(AmqpInterlocutor amqpInterlocutor,
                        EvtConsumer eventConsumer, AmqpQueue queue, Class<Evt> evtClass, CountDownLatch initLatch) throws IOException {
        this(amqpInterlocutor, eventConsumer, queue, evtClass, null, initLatch);
    }

    public AmqpConsumer(AmqpInterlocutor amqpInterlocutor,
                        EvtConsumer eventConsumer, AmqpQueue queue, Class<Evt> evtClass, String key) throws IOException {
        this(amqpInterlocutor, eventConsumer, queue, evtClass, key, null);
    }

    AmqpConsumer(AmqpInterlocutor amqpInterlocutor,
                 EvtConsumer eventConsumer, AmqpQueue queue, Class<Evt> evtClass, String key, CountDownLatch initLatch) throws IOException {
        this.amqpInterlocutor = amqpInterlocutor;
        this.eventConsumer = eventConsumer;
        this.channel = amqpInterlocutor.createAmqpChannelForConsume(queue, key);
        this.evtClass = evtClass;
        ofNullable(initLatch).ifPresent(CountDownLatch::countDown);
    }

    public AmqpConsumer<Evt, EvtConsumer> consumeEvents() {
        return launchConsumer(channel, AmqpConsumer.this::handle);
    }

    public AmqpConsumer<Evt, EvtConsumer> consumeEvents(int nb) {
        return launchConsumer(channel, AmqpConsumer.this::handle, nb);
    }

    public AmqpConsumer<Evt, EvtConsumer> consumeEvents(Consumer<Evt> eventHandler) {
        return launchConsumer(channel, eventHandler);
    }

    AmqpConsumer<Evt, EvtConsumer> launchConsumer(AmqpChannel channel, Consumer<Evt> eventHandler, final int nbEventsToConsume) {
        launchConsumer(channel, eventHandler, new ConsumerCriteria() {
            int nvReceivedEvents = 0;

            public void newEvent() {
                nvReceivedEvents++;
            }

            public boolean isValid() {
                return nvReceivedEvents < nbEventsToConsume;
            }
        });
        return this;
    }

    AmqpConsumer<Evt, EvtConsumer> launchConsumer(AmqpChannel channel, Consumer<Evt> eventHandler) {
        launchConsumer(channel, eventHandler, new ConsumerCriteria() {
            public void newEvent() {
            }

            public boolean isValid() {
                return true;
            }
        });
        return this;
    }

    private void launchConsumer(AmqpChannel channel, Consumer<Evt> eventHandler, ConsumerCriteria criteria) {
        try {
            logger.info("starting consuming events for {}", channel);
            consumerTag.set(channel.consume((body) -> eventHandler.accept(this.deserialize(body)), criteria, this::cancel));
        } catch (IOException ioe) {
            logger.error("exception during basicConsume", ioe);
        }
    }

    public void cancel() throws IOException {
        synchronized (channel) {
            if (consumerTag.get() != null) {
                channel.cancel(consumerTag.getAndSet(null));
            }
        }
    }

    public boolean isCanceled() {
        return consumerTag.get() == null;
    }

    void handle(Evt receivedEvent) {
        if (receivedEvent != null) {
            eventConsumer.accept(receivedEvent);
        }
    }

    public void shutdown() throws IOException {
        logger.info("shutting down consumer channel");
        try {
            if (!isCanceled()) {
                cancel();
            }
            channel.close();
        } catch (IOException | AlreadyClosedException ioe) {
            logger.error("exception during close", ioe);
        }
    }

    public Evt deserialize(byte[] rawJson) {
        try {
            return JsonObjectMapper.MAPPER.readValue(rawJson, evtClass);
        } catch (IOException e) {
            throw new DeserializeException(e);
        }
    }

    public void waitUntilChannelIsClosed() {
        while (channel.rabbitMqChannel.isOpen()) {
            try {
                Thread.sleep(WAIT_CLOSED_CHANNEL_DELAY_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
