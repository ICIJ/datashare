package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.InvalidURIException;
import redis.clients.jedis.util.JedisURIHelper;

import javax.net.ssl.SSLParameters;
import java.net.URI;
import java.util.Set;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_REDIS_ADDRESS;
import static org.icij.datashare.cli.DatashareCliOptions.REDIS_ADDRESS_OPT;

final class RedisPoolFactory {
    /** Lowercase only, because that is what Redisson accepts from the same option: an address one
     *  client takes and the other rejects boots the app halfway. */
    private static final Set<String> SUPPORTED_SCHEMES = Set.of("redis", "rediss");

    private static final String USER_INFO = "://[^@/]*@";
    private static final String REDACTED_USER_INFO = "://***@";

    private RedisPoolFactory() {}

    /**
     * The URI overload is the one that reads the scheme: in Jedis 2.9.0 the shorter ones hand
     * JedisFactory a hardcoded ssl=false, so a rediss:// address there connects in plaintext. Passing
     * the URI also keeps JedisFactory's own JedisURIHelper.isValid check, which a host/port call drops.
     */
    static JedisPool createPool(PropertiesProvider propertiesProvider) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        // Validate connections before handing them out: a connection killed server-side while idle
        // in the pool still looks alive locally (socket flags don't reflect a remote close), so
        // without this the next command on it fails with "Unexpected end of stream".
        poolConfig.setTestOnBorrow(true);
        URI address = validAddress(propertiesProvider.get(REDIS_ADDRESS_OPT).orElse(DEFAULT_REDIS_ADDRESS));
        return new JedisPool(poolConfig, address, Protocol.DEFAULT_TIMEOUT, Protocol.DEFAULT_TIMEOUT,
                null, hostVerifyingParameters(), null);
    }

    private static SSLParameters hostVerifyingParameters() {
        SSLParameters parameters = new SSLParameters();
        // Jedis leaves this null, which is TLS with no server identity check at all: the certificate
        // chain is validated but never matched against the host we asked for. Nothing else is set, so
        // the JDK's own protocol and cipher defaults stay in place (null means "leave unchanged").
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
    }

    /**
     * Refuses every address a Redis client in this app would fail on later, or would read as
     * plaintext for the wrong reason. The scheme now decides whether the session store is encrypted,
     * so an unrecognised one cannot be allowed to default to no encryption.
     */
    private static URI validAddress(String redisAddress) {
        URI address = parsedAddress(redisAddress);
        if (!JedisURIHelper.isValid(address) || !SUPPORTED_SCHEMES.contains(address.getScheme())) {
            throw invalidAddress(redisAddress, null);
        }
        return probedAddress(address, redisAddress);
    }

    private static URI parsedAddress(String redisAddress) {
        try {
            return URI.create(redisAddress);
        } catch (IllegalArgumentException e) {
            throw invalidAddress(redisAddress, e);
        }
    }

    /**
     * Calls the two helpers JedisFactory calls next so their raw failures cannot escape: both index
     * into a split() result, so a user info without a colon throws ArrayIndexOutOfBoundsException and
     * a non-numeric database throws NumberFormatException, out of a Guice provider and naming neither
     * Redis nor the option.
     */
    private static URI probedAddress(URI address, String redisAddress) {
        try {
            JedisURIHelper.getPassword(address);
            JedisURIHelper.getDBIndex(address);
            return address;
        } catch (RuntimeException e) {
            throw invalidAddress(redisAddress, e);
        }
    }

    /** The address is echoed without its user info, which would otherwise write the Redis password
     *  into the logs: this exception escapes createPool with a full stack trace at startup. */
    private static InvalidURIException invalidAddress(String redisAddress, Throwable cause) {
        String message = "invalid %s \"%s\"".formatted(REDIS_ADDRESS_OPT,
                redisAddress.replaceFirst(USER_INFO, REDACTED_USER_INFO));
        return cause == null ? new InvalidURIException(message) : new InvalidURIException(message, cause);
    }
}
