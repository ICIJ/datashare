package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.web.StatusResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_STATUS_ALLOWED_NETS;
import static org.icij.datashare.cli.DatashareCliOptions.STATUS_ALLOWED_NETS_OPT;

/**
 * HTTP filter that allows unauthenticated access to the {@code /api/status} endpoint
 * from trusted networks defined by CIDR notation.
 *
 * Authenticated users can reach the status endpoint from any IP address.
 * Unauthenticated requests are only allowed if the client IP belongs to one of the
 * configured CIDR subnets (localhost by default). All other unauthenticated requests
 * are rejected with a 403 response.
 *
 * This filter must be placed first in the filter chain so it can bypass
 * authentication for trusted networks.
 *
 * @see StatusResource
 */
public class StatusCidrFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(StatusCidrFilter.class);
    static final String STATUS_PATH = "/api/status";

    private final List<CidrBlock> allowedNets;
    private final StatusResource statusResource;

    /**
     * Creates a new filter with CIDR subnets read from the {@code statusAllowedNets} property.
     * All CIDR entries are parsed and validated eagerly at construction time.
     *
     * @param propertiesProvider provides the comma-separated CIDR list (defaults to {@code 127.0.0.0/8,::1/128})
     * @param statusResource     the resource called directly when access is granted
     * @throws InvalidCidrException if any CIDR entry is invalid
     */
    @Inject
    public StatusCidrFilter(PropertiesProvider propertiesProvider, StatusResource statusResource) {
        this.statusResource = statusResource;
        String nets = propertiesProvider.get(STATUS_ALLOWED_NETS_OPT).orElse(DEFAULT_STATUS_ALLOWED_NETS);
        this.allowedNets = Arrays.stream(nets.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(StatusCidrFilter::parseCidr)
                .collect(Collectors.toList());
        logger.info("status endpoint allows unauthenticated access from CIDRs: {}", allowedNets);
    }

    /**
     * Returns {@code true} only for the exact {@code /api/status} path.
     */
    @Override
    public boolean matches(String uri, Context context) {
        return STATUS_PATH.equals(uri);
    }

    /**
     * Applies the CIDR-based access control logic.
     *
     * If the user is already authenticated, delegates to the next filter.
     * If the client IP matches an allowed CIDR, calls StatusResource.getStatus directly,
     * bypassing the rest of the filter chain.
     * Otherwise, returns a 403 JSON error response.
     */
    @Override
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (context.currentUser() != null) {
            return nextFilter.get();
        }
        InetSocketAddress socketAddress = context.request().clientAddress();
        if (socketAddress != null) {
            InetAddress clientAddr = socketAddress.getAddress();
            if (clientAddr != null && isAllowed(clientAddr)) {
                return statusResource.getStatus(context);
            }
        }
        String clientIp = socketAddress != null && socketAddress.getAddress() != null
                ? socketAddress.getAddress().getHostAddress() : "unknown";
        logger.info("denied unauthenticated access to {} from {}", STATUS_PATH, clientIp);
        return new Payload("application/json", "{\"error\":\"Not authorized\"}", 403);
    }

    /**
     * Checks whether the given address belongs to any of the configured CIDR subnets.
     *
     * @param clientAddr the client IP address to check
     * @return {@code true} if the address is within at least one allowed subnet
     */
    private boolean isAllowed(InetAddress clientAddr) {
        for (CidrBlock block : allowedNets) {
            if (isInSubnet(clientAddr, block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests whether an IP address falls within a CIDR block.
     * Supports both IPv4 and IPv6; returns {@code false} on address family mismatch.
     *
     * @param addr the IP address to test
     * @param cidr the CIDR block in "address/prefix" notation (e.g. "10.0.0.0/8")
     * @return {@code true} if the address is inside the subnet
     */
    static boolean isInSubnet(InetAddress addr, String cidr) {
        try {
            return isInSubnet(addr, parseCidr(cidr));
        } catch (InvalidCidrException e) {
            return false;
        }
    }

    /**
     * Tests whether an IP address falls within a pre-parsed CIDR block.
     *
     * @param addr  the IP address to test
     * @param block the parsed CIDR block
     * @return {@code true} if the address is inside the subnet
     */
    private static boolean isInSubnet(InetAddress addr, CidrBlock block) {
        return isSameAddressFamily(addr, block.network)
                && prefixMatches(addr.getAddress(), block.network.getAddress(), block.prefixLength);
    }

    /**
     * Parses a CIDR string into a network address and prefix length.
     *
     * @param cidr the CIDR string (e.g. {@code "192.168.1.0/24"})
     * @return the parsed {@link CidrBlock}
     * @throws InvalidCidrException if the format is invalid, the host is unresolvable,
     *                              or the prefix length is out of range
     */
    private static CidrBlock parseCidr(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            throw new InvalidCidrException(cidr, "expected format \"address/prefix\"");
        }
        try {
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);
            int maxPrefix = network.getAddress().length * 8;
            if (prefixLength < 0 || prefixLength > maxPrefix) {
                throw new InvalidCidrException(cidr,
                        String.format("prefix length %d must be between 0 and %d", prefixLength, maxPrefix));
            }
            return new CidrBlock(network, prefixLength);
        } catch (UnknownHostException e) {
            throw new InvalidCidrException(cidr, "cannot resolve network address", e);
        } catch (NumberFormatException e) {
            throw new InvalidCidrException(cidr, "invalid prefix length", e);
        }
    }

    /**
     * Returns {@code true} if both addresses are the same IP version (both IPv4 or both IPv6).
     */
    private static boolean isSameAddressFamily(InetAddress addr, InetAddress network) {
        return addr.getAddress().length == network.getAddress().length;
    }

    /**
     * Compares two raw IP address byte arrays up to the given prefix length.
     *
     * @param addrBytes    the candidate address bytes
     * @param networkBytes the network address bytes
     * @param prefixLength the number of leading bits that must match
     * @return {@code true} if the first {@code prefixLength} bits are identical
     */
    private static boolean prefixMatches(byte[] addrBytes, byte[] networkBytes, int prefixLength) {
        int fullBytes = prefixLength / 8;
        if (!fullBytesMatch(addrBytes, networkBytes, fullBytes)) {
            return false;
        }
        int remainingBits = prefixLength % 8;
        return remainingBits == 0 || partialByteMatches(addrBytes[fullBytes], networkBytes[fullBytes], remainingBits);
    }

    /**
     * Compares the first {@code count} complete bytes of two address arrays.
     */
    private static boolean fullBytesMatch(byte[] addrBytes, byte[] networkBytes, int count) {
        for (int i = 0; i < count; i++) {
            if (addrBytes[i] != networkBytes[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares the most-significant {@code significantBits} of a single byte pair.
     *
     * @param addrByte       the byte from the candidate address
     * @param networkByte    the byte from the network address
     * @param significantBits number of high-order bits to compare (1-7)
     * @return {@code true} if the masked bits are equal
     */
    private static boolean partialByteMatches(byte addrByte, byte networkByte, int significantBits) {
        int mask = (0xFF << (8 - significantBits)) & 0xFF;
        return (addrByte & mask) == (networkByte & mask);
    }

    /**
     * Thrown when a CIDR notation string cannot be parsed.
     * The message includes the invalid value and the reason for rejection.
     */
    static class InvalidCidrException extends RuntimeException {
        InvalidCidrException(String cidr, String reason) {
            super(String.format("invalid CIDR notation \"%s\": %s", cidr, reason));
        }

        InvalidCidrException(String cidr, String reason, Throwable cause) {
            super(String.format("invalid CIDR notation \"%s\": %s", cidr, reason), cause);
        }
    }

    /**
     * Holds a parsed CIDR block: a network base address and its prefix length.
     */
    private static class CidrBlock {
        final InetAddress network;
        final int prefixLength;

        CidrBlock(InetAddress network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        @Override
        public String toString() {
            return network.getHostAddress() + "/" + prefixLength;
        }
    }
}
