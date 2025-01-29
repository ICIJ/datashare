package org.icij.datashare.asynctasks.bus.amqp;

import com.rabbitmq.client.BuiltinExchangeType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Enum that provides a registry for all used Queues/Exchanges/routing keys and more generally
 * all settings for the deployed AMQP strategies.
 */
public enum AmqpQueue {
	EVENT  ("exchangeMainEvents",  BuiltinExchangeType.FANOUT, "routingKeyMainEvents"),
	TASK_DLQ  ("exchangeDLQTasks", BuiltinExchangeType.DIRECT, "routingKeyDLQTasks"),
	TASK  ("exchangeMainTasks",  BuiltinExchangeType.DIRECT,"routingKeyMainTasks", Map.of(
			"x-queue-type", "quorum",
			"x-delivery-limit", 10,
			"x-consumer-timeout", 3600 * 1000), TASK_DLQ),
	MANAGER_EVENT_DLQ  ("exchangeDLQManagerEvents",  BuiltinExchangeType.DIRECT,"routingKeyDLQManagerEvents"),
	MANAGER_EVENT  ("exchangeManagerEvents",  BuiltinExchangeType.DIRECT,"routingKeyManagerEvents", new HashMap<>(), MANAGER_EVENT_DLQ),
	WORKER_EVENT("exchangeWorkerEvents", BuiltinExchangeType.FANOUT, "routingKeyWorkerEvents"),
	MONITORING("exchangeMonitoring", BuiltinExchangeType.DIRECT, "routingKeyMonitoring", Map.of(
			"x-message-ttl", 5000), null);

	public final String exchange;
	public final String routingKey;
	public final AmqpQueue deadLetterQueue;
	public final BuiltinExchangeType exchangeType;
	public final Map<String, Object> arguments;

	AmqpQueue(String exchange, BuiltinExchangeType exchangeType, String routingKey) {
		this(exchange, exchangeType, routingKey, new HashMap<>(), null);
	}

	AmqpQueue(String exchange, BuiltinExchangeType exchangeType, String routingKey, Map<String, Object> arguments, AmqpQueue deadLetterQueue) {
		this.exchange = exchange;
		this.exchangeType = exchangeType;
		this.routingKey = routingKey;
		this.deadLetterQueue = deadLetterQueue;
		this.arguments = Collections.unmodifiableMap(arguments);;
	}

	@Override public String toString() {
		return String.format("[%s: %s/%s]", name(), exchange, routingKey);
	}
}