package org.icij.datashare.com.bus.amqp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event saver based class : it can be used to handle events persistence.
 * @param <Evt> the events that are going to be treated by the saver.
 */
public abstract class EventSaver<Evt> {
	protected Logger logger = LoggerFactory.getLogger(this.getClass());
	public abstract void save(Evt evenement);
}
