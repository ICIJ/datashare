package org.icij.datashare.com.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EventSaver<Evt> {
	protected Logger logger = LoggerFactory.getLogger(this.getClass());
	public abstract void save(Evt evenement);
}
