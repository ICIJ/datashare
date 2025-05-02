package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
@JsonSubTypes({@JsonSubTypes.Type(ShutdownEvent.class),
		@JsonSubTypes.Type(ProgressEvent.class), @JsonSubTypes.Type(CancelEvent.class),
		@JsonSubTypes.Type(CancelledEvent.class), @JsonSubTypes.Type(ResultEvent.class),
		@JsonSubTypes.Type(ErrorEvent.class)})
public class Event implements Serializable {
	@Serial private static final long serialVersionUID = -2295266944323500399L;
	public static final int MAX_RETRIES_LEFT = 3;
	protected int retriesLeft = MAX_RETRIES_LEFT;
	public Date createdAt;

	public Event() {
		this(DatashareTime.getNow(), 3);
	}
	@JsonCreator
	public Event(@JsonProperty("createdAt") Date createdAt, @JsonProperty("retries") int retriesLeft) {
		this.createdAt = createdAt;
		this.retriesLeft = retriesLeft;
	}
	
	public boolean canBeReinjected() {
		return retriesLeft > 0;
	}

	public Event reinject() {
		retriesLeft--;
		return this;
	}

	public byte[] serialize() throws IOException {
		return JsonObjectMapper.MAPPER.writeValueAsBytes(this);
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}
}
