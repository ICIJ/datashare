package org.icij.datashare.com.bus;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractConsumer<Evt extends Event, EvtSaver extends EventSaver<Evt>> implements Deserializer<Evt> {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	protected final AmqpInterlocutor amqpInterlocutor;
	public final EvtSaver eventSaver;
	private final AmqpQueue queue;

	public AbstractConsumer(EvtSaver eventSaver, AmqpQueue queue) throws IOException, TimeoutException {
		this(AmqpInterlocutor.getInstance().createAmqpChannel(queue), eventSaver, queue);
	}
	
	protected AbstractConsumer(AmqpInterlocutor amqpInterlocutor,
							   EvtSaver eventSaver, AmqpQueue queue) {
		this.amqpInterlocutor = amqpInterlocutor;
		this.eventSaver = eventSaver;
		this.queue = queue;
	}

	public void consumeEvents() {consumingLoop(queue, AbstractConsumer.this::handle);}
	public void consumeEvents(int nb) {consumingLoop(queue, AbstractConsumer.this::handle, nb);}

	public <Evt extends Event> void consumingLoop(AmqpQueue queue, EventHandler<Evt> eventHandler, final int nbEventsToConsume) {
		consumingLoop(queue, eventHandler, new Criteria() {
			int nvReceivedEvents=0;
			public void newEvent() { nvReceivedEvents++; }
			public boolean isValid() { return nvReceivedEvents < nbEventsToConsume; }
		});
	}

	public <Evt extends Event> void consumingLoop(AmqpQueue queue, EventHandler<Evt> eventHandler) {
		consumingLoop(queue, eventHandler, new Criteria() {
			public void newEvent() {}
			public boolean isValid() { return true; }
		});
	}


	@SuppressWarnings("unchecked")
	private <Evt extends Event> void consumingLoop(AmqpQueue queue, EventHandler<Evt> eventHandler, Criteria criteria) {
		try {
			final Channel channel = amqpInterlocutor.createChannel();
			channel.basicConsume(queue.name(), new DefaultConsumer(channel) {
				@Override
				public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
					criteria.newEvent();
					if (criteria.isValid()) {
						try {
							eventHandler.handle((Evt) deserialize(body));
							channel.basicAck(envelope.getDeliveryTag(), false);
						} catch (RuntimeException rex) {
							channel.basicNack(envelope.getDeliveryTag(), true, false);
						}
					} else {
						channel.basicCancel(consumerTag);
					}
				}
			});
		} catch (IOException ioe) {
			logger.error("exception when creating channel or during basicConsume", ioe);
		}
	}

	interface EventHandler<Evt extends Event> {
		void handle(Evt receivedEvent);
	}

	void handle(Evt receivedEvent) {
		if (receivedEvent != null) {
			eventSaver.save(receivedEvent);
		}
	}

	public void shutdown() throws IOException, TimeoutException {
		logger.info("shutting down consumer");
		amqpInterlocutor.close();
	}

	private interface Criteria {
		void newEvent();
		boolean isValid();
	}
}
