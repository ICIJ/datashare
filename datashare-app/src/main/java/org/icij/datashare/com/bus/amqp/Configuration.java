package org.icij.datashare.com.bus.amqp;

import java.util.Properties;

import static java.util.Optional.ofNullable;

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
	public final boolean deadletter;

	public Configuration(Properties properties) {
		host = ofNullable(properties.getProperty("amqp.host")).orElse("localhost");
		port = Integer.parseInt(ofNullable(properties.getProperty("amqp.port")).orElse("5672"));
		user = properties.getProperty("amqp.user");
		password = properties.getProperty("amqp.password");
		deadletter = Boolean.parseBoolean(ofNullable(properties.getProperty("amqp.deadletter")).orElse("true"));
		nbMaxMessages = Integer.parseInt(ofNullable(properties.getProperty("amqp.nbMaxMessages")).orElse("100"));
		requeueDelay = Integer.parseInt(ofNullable(properties.getProperty("amqp.requeueDelay")).orElse("30"));
		String connectionRecoveryDelayStr = properties.getProperty("amqp.recoveryDelay");
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
		this.deadletter = true;
	}
	
	@Override public String toString() {
		return user + "@" + host + ":" + port + "-" + nbMaxMessages
				+ " max requeueDelay=" + requeueDelay;
	}
}
