package org.icij.datashare.com.bus;

public enum AmqpQueue {
	EVENT  ("exchangeMainEvents",  "routingKeyMainEvents"),
	TASK_DLQ  ("exchangeDLQTasks",  "routingKeyDLQTasks"),
	TASK  ("exchangeMainTasks",  "routingKeyMainTasks", TASK_DLQ);

	public final String exchange;
	public final String routingKey;
	public final AmqpQueue deadLetterQueue;

	AmqpQueue(String exchange, String routingKey) {
		this(exchange, routingKey, null);
	}
	AmqpQueue(String exchange, String routingKey, AmqpQueue deadLetterQueue) {
		this.exchange = exchange;
		this.routingKey = routingKey;
		this.deadLetterQueue = deadLetterQueue;
	}

	@Override public String toString() {
		return String.format("[%s: %s/%s]", name(), exchange, routingKey);
	}
}
