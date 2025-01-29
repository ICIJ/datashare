package org.icij.datashare.asynctasks.bus.amqp;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;

/**
 * Registry of all Configuration parameters for AMQP
 */
public class Configuration {
	private static final int DEFAULT_CONNECTION_RECOVERY_DELAY = 5000;
	private static final int DEFAULT_PREFETCH_NUMBER = 1;
	public final String host;
	public final int port;
	public final String user;
	public final String password;
	public final int nbMaxMessages;
	public final int requeueDelay;
	public final int connectionRecoveryDelay;
	public final boolean rabbitMq;
	public final boolean monitoring;

	public Configuration(URI amqpAddress) {
		assert "amqp".equals(amqpAddress.getScheme());
		host = amqpAddress.getHost();
		port = amqpAddress.getPort() == -1 ? 5672: amqpAddress.getPort();
		if (amqpAddress.getUserInfo() != null) {
			String[] userInfo = amqpAddress.getUserInfo().split(":");
			user = userInfo[0];
			password = userInfo[1];
		} else {
			user = "";
			password = "";
		}
		String query = ofNullable(amqpAddress.getQuery()).orElse("");
		Map<String, String> properties = query.isBlank() ?
				Collections.emptyMap():
				stream(query.split("&")).
                        collect(Collectors.toMap(kv -> kv.split("=")[0], kv -> kv.split("=")[1]));
		rabbitMq = Boolean.parseBoolean(ofNullable(properties.get("rabbitMq")).orElse("true"));
		monitoring = Boolean.parseBoolean(ofNullable(properties.get("monitoring")).orElse("false"));
		nbMaxMessages = Integer.parseInt(ofNullable(properties.get("nbMaxMessages")).orElse(String.valueOf(DEFAULT_PREFETCH_NUMBER)));
		requeueDelay = Integer.parseInt(ofNullable(properties.get("requeueDelay")).orElse("30"));
		String connectionRecoveryDelayStr = properties.get("recoveryDelay");
		connectionRecoveryDelay = connectionRecoveryDelayStr == null ?
				DEFAULT_CONNECTION_RECOVERY_DELAY : Integer.parseInt(connectionRecoveryDelayStr);
	}

	public Configuration(String host, int port, String user, String password, int nbMessageMax) {
		this.host = host;
		this.port = port;
		this.user = user;
		this.password = password;
		this.nbMaxMessages = nbMessageMax;
		this.requeueDelay = 30;
		this.connectionRecoveryDelay = DEFAULT_CONNECTION_RECOVERY_DELAY;
		this.rabbitMq = true;
		this.monitoring = false;
	}
	
	@Override public String toString() {
		return user + "@" + host + ":" + port + "-"  + nbMaxMessages
				+ " max requeueDelay=" + requeueDelay;
	}
}
