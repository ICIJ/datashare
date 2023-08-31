package org.icij.datashare.com.bus.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmCallback;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * AmqpChannel is handling publish errors with Publisher Confirm mechanism.
 * It encapsulates a channel, and expose consume/publish related functions.
 * see <a href="https://www.rabbitmq.com/confirms.html#publisher-confirms">rabbitMQ documentation</a>
 */
public class AmqpChannel {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final ConcurrentNavigableMap<Long, byte[]> outstandingConfirms = new ConcurrentSkipListMap<>();
	private final Channel rabbitMqChannel;
	final AmqpQueue queue;
	private final ConfirmCallback cleanOutstandingConfirms = (sequenceNumber, multiple) -> {
		if (multiple) {
			ConcurrentNavigableMap<Long, byte[]> confirmed = outstandingConfirms.headMap(sequenceNumber, true);
			confirmed.clear();
		} else {
			outstandingConfirms.remove(sequenceNumber);
		}
	};
	
	public AmqpChannel(Channel channel, AmqpQueue queue) {
		this.rabbitMqChannel = channel;
		channel.addConfirmListener(cleanOutstandingConfirms, (sequenceNumber, multiple) -> {
			byte[] body = outstandingConfirms.get(sequenceNumber);
			logger.error("Message with body {} has been nack-ed. Sequence number: {}, multiple: {}", new String(body), sequenceNumber, multiple);
			cleanOutstandingConfirms.handle(sequenceNumber, multiple);
		});
		this.queue = queue;
	}

	void publish(Event event) throws IOException {
		rabbitMqChannel.basicPublish(queue.exchange, queue.routingKey, null, event.serialize());
	}

	void cancel(String consumerTag) throws IOException {
		this.rabbitMqChannel.basicCancel(consumerTag);
	}

	String consume(Consumer<byte[]> bodyHandler, ConsumerCriteria criteria, CancelFunction cancelCallback) throws IOException {
		return this.rabbitMqChannel.basicConsume(queue.name(), new DefaultConsumer(rabbitMqChannel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
				try {
					bodyHandler.accept(body);
					rabbitMqChannel.basicAck(envelope.getDeliveryTag(), false);
				} catch (RuntimeException rex) {
					rabbitMqChannel.basicNack(envelope.getDeliveryTag(), false, false);
				}
				criteria.newEvent();
				if (!criteria.isValid()) {
					cancelCallback.cancel();
				}
			}
		});
	}

	public void close() throws IOException {
		try {
			if (rabbitMqChannel.isOpen()) {
				rabbitMqChannel.close();
				logger.info("channel {} was open it has been closed", this);
			}
		} catch (TimeoutException e) {
			throw new RuntimeException(e);
		}
	}

    @Override public String toString() {
        return queue.toString();
    }
	@FunctionalInterface
	interface CancelFunction {
		void cancel() throws IOException;
	}
}
