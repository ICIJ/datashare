package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Request;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.web.StatusResource;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StatusCidrFilterTest {
    private final Payload nextPayload = Payload.ok();
    private final PayloadSupplier nextFilter = () -> nextPayload;
    private final Payload statusPayload = new Payload("application/json", "{\"status\":\"ok\"}", 200);
    private final Context context = mock(Context.class);
    private final Request request = mock(Request.class);
    private final StatusResource statusResource = mock(StatusResource.class);

    @Before
    public void setUp() throws Exception {
        when(context.request()).thenReturn(request);
        when(statusResource.getStatus(context)).thenReturn(statusPayload);
    }

    @Test
    public void test_matches_only_status_path() {
        StatusCidrFilter filter = createFilter(new HashMap<>());
        assertThat(filter.matches("/api/status", null)).isTrue();
        assertThat(filter.matches("/api/status/", null)).isFalse();
        assertThat(filter.matches("/api/users", null)).isFalse();
        assertThat(filter.matches("/api/", null)).isFalse();
        assertThat(filter.matches("/other", null)).isFalse();
    }

    @Test
    public void test_authenticated_user_passes_through() throws Exception {
        StatusCidrFilter filter = createFilter(new HashMap<>());
        when(context.currentUser()).thenReturn(mock(net.codestory.http.security.User.class));
        setClientAddress("127.0.0.1");

        Payload result = filter.apply("/api/status", context, nextFilter);
        assertThat(result).isSameAs(nextPayload);
    }

    @Test
    public void test_localhost_ipv4_allowed_by_default() throws Exception {
        StatusCidrFilter filter = createFilter(new HashMap<>());
        when(context.currentUser()).thenReturn(null);
        setClientAddress("127.0.0.1");

        Payload result = filter.apply("/api/status", context, nextFilter);
        assertThat(result).isSameAs(statusPayload);
    }

    @Test
    public void test_localhost_ipv6_allowed_by_default() throws Exception {
        StatusCidrFilter filter = createFilter(new HashMap<>());
        when(context.currentUser()).thenReturn(null);
        setClientAddress("::1");

        Payload result = filter.apply("/api/status", context, nextFilter);
        assertThat(result).isSameAs(statusPayload);
    }

    @Test
    public void test_unallowed_ip_returns_403() throws Exception {
        StatusCidrFilter filter = createFilter(new HashMap<>());
        when(context.currentUser()).thenReturn(null);
        setClientAddress("192.168.1.100");

        Payload result = filter.apply("/api/status", context, nextFilter);
        assertThat(result.code()).isEqualTo(403);
        assertThat((String) result.rawContent()).contains("Not authorized");
    }

    @Test
    public void test_custom_cidr_allows_subnet() throws Exception {
        HashMap<String, Object> props = new HashMap<>();
        props.put("statusAllowedNets", "10.0.0.0/8");
        StatusCidrFilter filter = createFilter(props);
        when(context.currentUser()).thenReturn(null);
        setClientAddress("10.1.2.3");

        Payload result = filter.apply("/api/status", context, nextFilter);
        assertThat(result).isSameAs(statusPayload);
    }

    @Test
    public void test_custom_cidr_rejects_outside_subnet() throws Exception {
        HashMap<String, Object> props = new HashMap<>();
        props.put("statusAllowedNets", "10.0.0.0/8");
        StatusCidrFilter filter = createFilter(props);
        when(context.currentUser()).thenReturn(null);
        setClientAddress("192.168.1.1");

        Payload result = filter.apply("/api/status", context, nextFilter);
        assertThat(result.code()).isEqualTo(403);
        assertThat((String) result.rawContent()).contains("Not authorized");
    }

    @Test
    public void test_multiple_cidrs() throws Exception {
        HashMap<String, Object> props = new HashMap<>();
        props.put("statusAllowedNets", "10.0.0.0/8, 172.16.0.0/12");
        StatusCidrFilter filter = createFilter(props);
        when(context.currentUser()).thenReturn(null);

        setClientAddress("10.5.5.5");
        assertThat(filter.apply("/api/status", context, nextFilter)).isSameAs(statusPayload);

        setClientAddress("172.20.1.1");
        assertThat(filter.apply("/api/status", context, nextFilter)).isSameAs(statusPayload);

        setClientAddress("192.168.1.1");
        Payload rejected = filter.apply("/api/status", context, nextFilter);
        assertThat(rejected.code()).isEqualTo(403);
        assertThat((String) rejected.rawContent()).contains("Not authorized");
    }

    @Test
    public void test_cidr_matching_ipv4() throws Exception {
        // /0 matches everything
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("192.168.1.1"), "0.0.0.0/0")).isTrue();

        // /32 matches exact address
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("10.0.0.1"), "10.0.0.1/32")).isTrue();
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("10.0.0.2"), "10.0.0.1/32")).isFalse();

        // /24 subnet boundary
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("192.168.1.254"), "192.168.1.0/24")).isTrue();
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("192.168.2.1"), "192.168.1.0/24")).isFalse();

        // /16 subnet
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("172.16.255.255"), "172.16.0.0/16")).isTrue();
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("172.17.0.0"), "172.16.0.0/16")).isFalse();
    }

    @Test
    public void test_cidr_matching_ipv6() throws Exception {
        // Loopback
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("::1"), "::1/128")).isTrue();
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("::2"), "::1/128")).isFalse();

        // /64 prefix
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("fe80::1"), "fe80::/64")).isTrue();
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("fe80::ffff:ffff:ffff:ffff"), "fe80::/64")).isTrue();

        // IPv4 vs IPv6 mismatch
        assertThat(StatusCidrFilter.isInSubnet(
                InetAddress.getByName("127.0.0.1"), "::1/128")).isFalse();
    }

    @Test(expected = StatusCidrFilter.InvalidCidrException.class)
    public void test_invalid_cidr_fails_at_construction() {
        HashMap<String, Object> props = new HashMap<>();
        props.put("statusAllowedNets", "not-a-cidr");
        createFilter(props);
    }

    private StatusCidrFilter createFilter(HashMap<String, Object> properties) {
        PropertiesProvider provider = new PropertiesProvider(properties);
        return new StatusCidrFilter(provider, statusResource);
    }

    private void setClientAddress(String ip) throws Exception {
        InetAddress addr = InetAddress.getByName(ip);
        InetSocketAddress socketAddr = new InetSocketAddress(addr, 12345);
        when(request.clientAddress()).thenReturn(socketAddr);
    }
}
