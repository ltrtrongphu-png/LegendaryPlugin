package com.legendary.plugin.modules.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiDdosAddressTest {

    @Test
    void matchesIpv4AndIpv6Cidrs() throws Exception {
        var ipv4 = AntiDdosManager.parseCidrs(List.of("192.0.2.0/24")).get(0);
        assertTrue(ipv4.matches(InetAddress.getByName("192.0.2.42")));
        assertFalse(ipv4.matches(InetAddress.getByName("192.0.3.1")));

        var ipv6 = AntiDdosManager.parseCidrs(List.of("2001:db8:abcd:12::/64")).get(0);
        assertTrue(ipv6.matches(InetAddress.getByName("2001:db8:abcd:12::1234")));
        assertFalse(ipv6.matches(InetAddress.getByName("2001:db8:abcd:13::1")));
    }

    @Test
    void normalizesIpv6AddressesToTheirNetworkPrefix() throws Exception {
        assertTrue(AntiDdosManager.normalizeAddress(
            InetAddress.getByName("2001:db8:abcd:12::1")).startsWith("v6/64:"));
    }
}
