package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.time.DatashareTime;

import java.io.IOException;
import java.io.Serializable;
import java.util.Date;

/**
 * Base event for AMQP.
 * It provides creation timestamp and ttl that can be used for error handling and republishing/DLQ strategies.
 */
public class Event implements Serializable {
	private static final long serialVersionUID = -2295266944323500399L;
	protected int ttl = 3;
	public final Date creationDate;

	public Event() {
		this(DatashareTime.getNow(), 3);
	}
	@JsonCreator
	public Event(@JsonProperty("creationDate") Date creationDate, @JsonProperty("ttl") int ttl) {
		this.creationDate = creationDate;
		this.ttl = ttl;
	}
	
	public boolean canBeReinjected() {
		return ttl > 0;
	}

	public Event reinject() {
		ttl--;
		return this;
	}

	public byte[] serialize() throws IOException {
		return JsonObjectMapper.MAPPER.writeValueAsString(this).getBytes();
	}
}
