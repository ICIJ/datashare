package org.icij.datashare.com.bus;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.rabbitmq.client.Channel;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmqpChannel {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	final Channel rabbitMqChannel;
	final Queue<Event> erroredEvents;
	final AmqpQueue queue;
	
	public AmqpChannel(Channel rabbitMqCanal, AmqpQueue queue) {
		this(rabbitMqCanal, queue, new ConcurrentLinkedQueue<>());
	}

	public AmqpChannel(Channel channel, AmqpQueue queue, Queue<Event> evenementsEnErreur) {
		this.erroredEvents = evenementsEnErreur;
		this.rabbitMqChannel = channel;
		this.queue = queue;
	}
	
	public int nbErroredEvents() { return erroredEvents.size(); }

	public void add(Event event) {
		erroredEvents.offer(event);
		final int size = erroredEvents.size();
		if (size % 1000 == 0) {
			logger.error("There is " + size + " elements in the error queue " + queue);
		}
	}

	public synchronized void emptyErrorQueue(OutputStream out){
		logger.info("Starting to handle errored messages");
		final Iterator<Event> iterator = erroredEvents.iterator();
		while(iterator.hasNext()){
			writeAndFlush(out, appendNext50Messages(iterator));
		}
		logger.info("Errored messages handled");
	}

	private StringBuilder appendNext50Messages(final Iterator<Event> iterator) {
		final StringBuilder sb = new StringBuilder();
		for (int nbMessagesLus = 0; iterator.hasNext() && nbMessagesLus < 50; nbMessagesLus++) {
			Event evt = iterator.next();
			try {
				sb.append(JsonObjectMapper.MAPPER.writeValueAsString(evt)).append('\n');
			} catch (JsonProcessingException e) {
				logger.info("error while serializing json {}", evt);
			}
			iterator.remove();
		}
		return sb;
	}

	private void writeAndFlush(OutputStream out, final StringBuilder sb) {
		try {
			out.write(sb.toString().getBytes());
			out.flush();
		} catch (Exception e) {
			logger.info("Write error from " + sb.toString());
		}
	}

    @Override public String toString() {
        return rabbitMqChannel.toString();
    }
}
