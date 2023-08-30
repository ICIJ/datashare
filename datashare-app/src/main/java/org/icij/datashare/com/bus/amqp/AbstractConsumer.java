package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for consumer implementation.
 * @param <Evt> The event class that are going to be consumed by the consumer
 * @param <EvtSaver> The class for handling the received event class
 */
public abstract class AbstractConsumer<Evt extends Event, EvtSaver extends EventSaver<Evt>> implements Deserializer<Evt> {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	protected final AmqpInterlocutor amqpInterlocutor;
	public final EvtSaver eventSaver;
	private final AmqpChannel channel;
	private final AtomicReference<String> consumerTag = new AtomicReference<>();

	public AbstractConsumer(EvtSaver eventSaver, AmqpQueue queue) throws IOException, TimeoutException {
		this(AmqpInterlocutor.getInstance(), eventSaver, queue);
	}
	
	protected AbstractConsumer(AmqpInterlocutor amqpInterlocutor,
							   EvtSaver eventSaver, AmqpQueue queue) throws IOException, TimeoutException {
		this.amqpInterlocutor = amqpInterlocutor;
		this.eventSaver = eventSaver;
		this.channel = amqpInterlocutor.createAmqpChannelForConsume(queue);
	}

	public void consumeEvents() {
		launchConsumer(channel, AbstractConsumer.this::handle);}
	public void consumeEvents(int nb) {
		launchConsumer(channel, AbstractConsumer.this::handle, nb);}

	public void launchConsumer(AmqpChannel channel, EventHandler<Evt> eventHandler, final int nbEventsToConsume) {
		launchConsumer(channel, eventHandler, new Criteria() {
			int nvReceivedEvents=0;
			public void newEvent() { nvReceivedEvents++; }
			public boolean isValid() { return nvReceivedEvents < nbEventsToConsume; }
		});
	}

	public void cancel() throws IOException {
		channel.rabbitMqChannel.basicCancel(consumerTag.getAndSet(null));
	}

	public void launchConsumer(AmqpChannel channel, EventHandler<Evt> eventHandler) {
		launchConsumer(channel, eventHandler, new Criteria() {
			public void newEvent() {}
			public boolean isValid() { return true; }
		});
	}

	private void launchConsumer(AmqpChannel channel, EventHandler<Evt> eventHandler, Criteria criteria) {
		try {
			logger.info("starting consuming events for {}", channel);
			consumerTag.set(channel.rabbitMqChannel.basicConsume(channel.queue.name(), new DefaultConsumer(channel.rabbitMqChannel) {
				@Override
				public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
					try {
						eventHandler.handle(deserialize(body));
						channel.rabbitMqChannel.basicAck(envelope.getDeliveryTag(), false);
					} catch (RuntimeException rex) {
						channel.rabbitMqChannel.basicNack(envelope.getDeliveryTag(), false, false);
					}
					criteria.newEvent();
					if (!criteria.isValid()) {
						cancel();
					}
				}
			}));
		} catch (IOException ioe) {
			logger.error("exception when creating channel or during basicConsume", ioe);
		}
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

	private interface Criteria {
		void newEvent();
		boolean isValid();
	}

	public Evt deserialize(byte[] rawJson) throws IOException {
		 return JsonObjectMapper.MAPPER.readValue(rawJson, new TypeReference<>() {});
	}
}
