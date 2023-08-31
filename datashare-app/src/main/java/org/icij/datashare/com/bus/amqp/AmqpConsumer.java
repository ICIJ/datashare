package org.icij.datashare.com.bus.amqp;

import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Class for consumer implementation. Is orchestrates the received events handling : deserialize and save.
 * @param <Evt> The event class that are going to be consumed by the consumer
 * @param <EvtSaver> The class for handling the received event class
 */
public class AmqpConsumer<Evt extends Event, EvtSaver extends EventSaver<Evt>> implements Deserializer<Evt> {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	protected final AmqpInterlocutor amqpInterlocutor;
	public final EvtSaver eventSaver;
	private final AmqpChannel channel;
	private final AtomicReference<String> consumerTag = new AtomicReference<>();
	private final Class<Evt> evtClass;

	public AmqpConsumer(EvtSaver eventSaver, AmqpQueue queue, Class<Evt> evtClass) throws IOException {
		this(AmqpInterlocutor.getInstance(), eventSaver, queue, evtClass);
	}
	
	protected AmqpConsumer(AmqpInterlocutor amqpInterlocutor,
						   EvtSaver eventSaver, AmqpQueue queue, Class<Evt> evtClass) throws IOException {
		this.amqpInterlocutor = amqpInterlocutor;
		this.eventSaver = eventSaver;
		this.channel = amqpInterlocutor.createAmqpChannelForConsume(queue);
		this.evtClass = evtClass;
	}

	public void consumeEvents() {
		launchConsumer(channel, AmqpConsumer.this::handle);}
	public void consumeEvents(int nb) {
		launchConsumer(channel, AmqpConsumer.this::handle, nb);}

	public void launchConsumer(AmqpChannel channel, EventHandler<Evt> eventHandler, final int nbEventsToConsume) {
		launchConsumer(channel, eventHandler, new ConsumerCriteria() {
			int nvReceivedEvents=0;
			public void newEvent() { nvReceivedEvents++; }
			public boolean isValid() { return nvReceivedEvents < nbEventsToConsume; }
		});
	}

	public void launchConsumer(AmqpChannel channel, EventHandler<Evt> eventHandler) {
		launchConsumer(channel, eventHandler, new ConsumerCriteria() {
			public void newEvent() {}
			public boolean isValid() { return true; }
		});
	}

	private void launchConsumer(AmqpChannel channel, EventHandler<Evt> eventHandler, ConsumerCriteria criteria) {
		try {
			logger.info("starting consuming events for {}", channel);
			consumerTag.set(channel.consume((body) -> eventHandler.handle(this.deserialize(body)), criteria, this::cancel));
		} catch (IOException ioe) {
			logger.error("exception during basicConsume", ioe);
		}
	}

	public void cancel() throws IOException {
		channel.cancel(consumerTag.getAndSet(null));
	}

	public boolean isCanceled() {
		return consumerTag.get() == null;
	}

	interface EventHandler<Evt extends Event> {
		void handle(Evt receivedEvent);
	}

	void handle(Evt receivedEvent) {
		if (receivedEvent != null) {
			eventSaver.save(receivedEvent);
		}
	}

	public void shutdown() throws IOException {
		logger.info("shutting down consumer channel");
		if (!isCanceled()) {
			cancel();
		}
		channel.close();
	}

	public Evt deserialize(byte[] rawJson) {
		try {
			return JsonObjectMapper.MAPPER.readValue(rawJson, evtClass);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
