package org.icij.datashare.com.bus;

import com.rabbitmq.client.BuiltinExchangeType;

/**
 * Enum that provides a registry for all used Queues/Exchanges/routing keys and more generally
 * all settings for the deployed AMQP strategies.
 */
public enum AmqpQueue {

	EVENT  ("exchangeMainEvents",  BuiltinExchangeType.FANOUT, "routingKeyMainEvents"),
	TASK_DLQ  ("exchangeDLQTasks", BuiltinExchangeType.DIRECT, "routingKeyDLQTasks"),
	TASK  ("exchangeMainTasks",  BuiltinExchangeType.DIRECT,"routingKeyMainTasks", TASK_DLQ);

	public final String exchange;
	public final String routingKey;
	public final AmqpQueue deadLetterQueue;
	public final BuiltinExchangeType exchangeType;

	AmqpQueue(String exchange, BuiltinExchangeType exchangeType, String routingKey) {
		this(exchange, exchangeType, routingKey, null);
	}
	AmqpQueue(String exchange, BuiltinExchangeType exchangeType, String routingKey, AmqpQueue deadLetterQueue) {
		this.exchange = exchange;
		this.exchangeType = exchangeType;
		this.routingKey = routingKey;
		this.deadLetterQueue = deadLetterQueue;
	}

	@Override public String toString() {
		return String.format("[%s: %s/%s]", name(), exchange, routingKey);
	}
}
