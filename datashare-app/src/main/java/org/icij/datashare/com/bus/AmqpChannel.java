package org.icij.datashare.com.bus;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;

/**
 * AmqpChannel is handling publish errors with Publisher Confirm mechanism.
 * see <a href="https://www.rabbitmq.com/confirms.html#publisher-confirms">rabbitMQ documentation</a>
 */
public class AmqpChannel {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final ConcurrentNavigableMap<Long, String> outstandingConfirms = new ConcurrentSkipListMap<>();
	final Channel rabbitMqChannel;
	final AmqpQueue queue;
	ConfirmCallback cleanOutstandingConfirms = (sequenceNumber, multiple) -> {
		if (multiple) {
			ConcurrentNavigableMap<Long, String> confirmed = outstandingConfirms.headMap(sequenceNumber, true);
			confirmed.clear();
		} else {
			outstandingConfirms.remove(sequenceNumber);
		}
	};
	
	public AmqpChannel(Channel channel, AmqpQueue queue) {
		this.rabbitMqChannel = channel;
		channel.addConfirmListener(cleanOutstandingConfirms, (sequenceNumber, multiple) -> {
			String body = outstandingConfirms.get(sequenceNumber);
			logger.error("Message with body {} has been nack-ed. Sequence number: {}, multiple: {}", body, sequenceNumber, multiple);
			cleanOutstandingConfirms.handle(sequenceNumber, multiple);
		});
		this.queue = queue;
	}

	void publish(Event event) throws IOException {
		rabbitMqChannel.basicPublish(queue.exchange, queue.routingKey, null, event.serialize());
	}

	public void close() throws IOException {
		try {
			this.rabbitMqChannel.close();
		} catch (TimeoutException e) {
			throw new RuntimeException(e);
		}
	}

    @Override public String toString() {
        return queue.toString();
    }
}
