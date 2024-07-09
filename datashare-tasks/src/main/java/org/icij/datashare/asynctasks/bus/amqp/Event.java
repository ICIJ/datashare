package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.fasterxml.jackson.databind.jsontype.impl.ClassNameIdResolver;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.time.DatashareTime;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Base event.
 * It provides creation timestamp and ttl that can be used for error handling and republishing/DLQ strategies.
 * For name mapping, subclasses can use the @JsonTypeName annotation.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
public class Event implements Serializable {
	@Serial private static final long serialVersionUID = -2295266944323500399L;
	protected int ttl = 3;
	public final Date createdAt;

	public Event() {
		this(DatashareTime.getNow(), 3);
	}
	@JsonCreator
	public Event(@JsonProperty("createdAt") Date createdAt, @JsonProperty("ttl") int ttl) {
		this.createdAt = createdAt;
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
		return JsonObjectMapper.MAPPER.writeValueAsBytes(this);
	}
}
