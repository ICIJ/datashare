package org.icij.datashare.asynctasks.bus.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmCallback;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.icij.datashare.asynctasks.NackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * AmqpChannel is handling publish errors with Publisher Confirm mechanism.
 * It encapsulates a channel, and expose consume/publish related functions.
 * see <a href="https://www.rabbitmq.com/confirms.html#publisher-confirms">rabbitMQ documentation</a>
 */
public class AmqpChannel {
	private static final Random rand = new Random();
	public static final String WORKER_PREFIX = "worker";
	private final boolean durable = true;
	private final boolean exclusive;
	private final boolean autoDelete;
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final ConcurrentNavigableMap<Long, byte[]> outstandingConfirms = new ConcurrentSkipListMap<>();
	final Channel rabbitMqChannel;
	final AmqpQueue queue;
	private final int randomQueueNumber;
	private final String key;

	private final ConfirmCallback cleanOutstandingConfirms = (sequenceNumber, multiple) -> {
		if (multiple) {
			ConcurrentNavigableMap<Long, byte[]> confirmed = outstandingConfirms.headMap(sequenceNumber, true);
			confirmed.clear();
		} else {
			outstandingConfirms.remove(sequenceNumber);
		}
	};
	
	public AmqpChannel(Channel channel, AmqpQueue queue) {
		this(channel, queue, null);
	}

	public AmqpChannel(Channel channel, AmqpQueue queue, String key) {
		this.rabbitMqChannel = channel;
		channel.addConfirmListener(cleanOutstandingConfirms, (sequenceNumber, multiple) -> {
			byte[] body = outstandingConfirms.get(sequenceNumber);
			logger.error("Message with body {} has been nack-ed. Sequence number: {}, multiple: {}", new String(body), sequenceNumber, multiple);
			cleanOutstandingConfirms.handle(sequenceNumber, multiple);
		});
		this.queue = queue;
		this.randomQueueNumber = rand.nextInt(1000);
		this.key = key;
		this.autoDelete = BuiltinExchangeType.FANOUT.equals(queue.exchangeType);
		this.exclusive = BuiltinExchangeType.FANOUT.equals(queue.exchangeType);
	}

	void publish(Event event) throws IOException {
		rabbitMqChannel.basicPublish(queue.exchange, queue.routingKey, null, event.serialize());
	}

	public void publish(Event event, String key) throws IOException {
		rabbitMqChannel.basicPublish(queue.exchange, key, null, event.serialize());
	}

	String consume(Consumer<byte[]> bodyHandler, ConsumerCriteria criteria, CancelFunction cancelCallback) throws IOException {
		return this.rabbitMqChannel.basicConsume(queueName(WORKER_PREFIX), new DefaultConsumer(rabbitMqChannel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
				try {
					bodyHandler.accept(body);
					rabbitMqChannel.basicAck(envelope.getDeliveryTag(), false);
				} catch (Deserializer.DeserializeException jsonException) {
					logger.warn("exception while deserializing json. Sending nack without requeue", jsonException);
					rabbitMqChannel.basicNack(envelope.getDeliveryTag(), false, false);
				} catch (NackException nackEx) {
                    logger.warn("exception while accepting event. Sending nack with requeue={}", nackEx.requeue, nackEx);
					rabbitMqChannel.basicNack(envelope.getDeliveryTag(), false, nackEx.requeue);
				}
				criteria.newEvent();
				if (!criteria.isValid()) {
					cancelCallback.cancel();
				}
			}
		});
	}

	void cancel(String consumerTag) throws IOException {
		this.rabbitMqChannel.basicCancel(consumerTag);
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

	String queueName(String prefix) {
		return BuiltinExchangeType.FANOUT.equals(queue.exchangeType) ?
				String.format("%s-%s-%s-%d-%d-%d", queue.name(), prefix, getHostname(), ProcessHandle.current().pid(), Thread.currentThread().getId(), randomQueueNumber) :
				ofNullable(key).map(q -> String.format("%s.%s", queue.name(), key)).orElse(queue.name());
	}

    @Override public String toString() {
        return String.format("%s (%s)", queueName(WORKER_PREFIX), queue);
    }

	void initForPublish() throws IOException {
		rabbitMqChannel.exchangeDeclare(queue.exchange, queue.exchangeType, durable);
	}

	void initForConsume(boolean rabbitMq, int nbMaxMessages) throws IOException {
		Map<String, Object> queueArguments = new HashMap<>() {{
			if (rabbitMq) {
				if (queue.deadLetterQueue != null) {
					put("x-dead-letter-exchange", queue.deadLetterQueue.exchange);
					put("x-dead-letter-routing-key", queue.deadLetterQueue.routingKey);
				}
				putAll(queue.arguments);
			}
		}};
		String queueName = queueName(WORKER_PREFIX);
		rabbitMqChannel.exchangeDeclare(queue.exchange, queue.exchangeType, durable);
		rabbitMqChannel.queueDeclare(queueName, durable, exclusive, autoDelete, queueArguments);
		rabbitMqChannel.queueBind(queueName, queue.exchange, ofNullable(key).orElse(queue.routingKey));
		rabbitMqChannel.basicQos(nbMaxMessages);
	}

	@FunctionalInterface
	interface CancelFunction {
		void cancel() throws IOException;
	}

	static synchronized String getHostname() {
		return getHostname("hostname");
	}

	static synchronized String getHostname(String hostnameCommandName) {
		Logger log = LoggerFactory.getLogger(AmqpChannel.class);
		try {
			Process process = new ProcessBuilder(hostnameCommandName.split(" ")).start();
			int exitVal = process.waitFor();
			if (exitVal != 0) {
				log.error("hostname command exited with {} returning uuid", exitVal);
				return UUID.randomUUID().toString();
			}
			return new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();
		} catch (IOException | InterruptedException e) {
			log.error("call to hostname failed returning uuid", e);
			return UUID.randomUUID().toString();
		}
	}
}
