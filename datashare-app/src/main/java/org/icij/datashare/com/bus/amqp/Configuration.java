package org.icij.datashare.com.bus.amqp;

import java.net.URI;
import java.net.URL;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * Registry of all Configuration parameters for AMQP
 */
public class Configuration {
	private static final int DEFAULT_CONNECTION_RECOVERY_DELAY = 5000;
	public final String host;
	public final int port;
	public final String user;
	public final String password;
	public final int nbMaxMessages;
	public final int requeueDelay;
	public final int connectionRecoveryDelay;
	public final boolean deadLetter;

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
		deadLetter = Boolean.parseBoolean(ofNullable(properties.get("deadLetter")).orElse("true"));
		nbMaxMessages = Integer.parseInt(ofNullable(properties.get("nbMaxMessages")).orElse("100"));
		requeueDelay = Integer.parseInt(ofNullable(properties.get("requeueDelay")).orElse("30"));
		String connectionRecoveryDelayStr = properties.get("recoveryDelay");
		connectionRecoveryDelay = connectionRecoveryDelayStr == null ?
				DEFAULT_CONNECTION_RECOVERY_DELAY : Integer.parseInt(connectionRecoveryDelayStr);
	}

	Configuration(String host, int port, String user, String password, int nbMessageMax) {
		this.host = host;
		this.port = port;
		this.user = user;
		this.password = password;
		this.nbMaxMessages = nbMessageMax;
		this.requeueDelay = 30;
		this.connectionRecoveryDelay = DEFAULT_CONNECTION_RECOVERY_DELAY;
		this.deadLetter = true;
	}
	
	@Override public String toString() {
		return user + "@" + host + ":" + port + "-"  + nbMaxMessages
				+ " max requeueDelay=" + requeueDelay;
	}
}
