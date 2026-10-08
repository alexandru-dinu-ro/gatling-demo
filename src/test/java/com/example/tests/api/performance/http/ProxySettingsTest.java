package com.example.tests.api.performance.http;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class ProxySettingsTest {

    private static final String TENANT = "tenant.uap.cyberark.cloud";

    @Test
    public void noneMeansDirect() {
        for (String value : new String[]{"none", "NONE", "", "  ", null}) {
            ProxySettings proxy = ProxySettings.parse(value, "none");
            assertFalse(proxy.enabled(), "should be disabled for: " + value);
            assertFalse(proxy.appliesTo(TENANT));
            assertEquals(proxy.toString(), "none");
        }
    }

    @DataProvider
    public Object[][] acceptedAddresses() {
        return new Object[][]{
                {"http://proxy.corp.example:8080"},
                {"http://proxy.corp.example:8080/"},
                {"proxy.corp.example:8080"},
                {"  HTTP://proxy.corp.example:8080  "},
        };
    }

    @Test(dataProvider = "acceptedAddresses")
    public void plainHttpAddressesAreAccepted(String value) {
        ProxySettings proxy = ProxySettings.parse(value, "none");

        assertTrue(proxy.enabled());
        assertEquals(proxy.host(), "proxy.corp.example");
        assertEquals(proxy.port(), 8080);
        assertTrue(proxy.appliesTo(TENANT));
    }

    @Test
    public void invalidAddressesAreRejected() {
        assertTrue(expectThrows(IllegalArgumentException.class,
                () -> ProxySettings.parse("https://proxy:8443", "none")).getMessage().contains("must use http://"));
        assertTrue(expectThrows(IllegalArgumentException.class,
                () -> ProxySettings.parse("http://user:secret@proxy:8080", "none")).getMessage().contains("credentials"));
        assertTrue(expectThrows(IllegalArgumentException.class,
                () -> ProxySettings.parse("http://proxy", "none")).getMessage().contains("host:port"));
    }

    @Test
    public void credentialsNeverAppearInTheMessage() {
        String message = expectThrows(IllegalArgumentException.class,
                () -> ProxySettings.parse("http://user:secret@proxy:8080", "none")).getMessage();

        assertFalse(message.contains("secret"), message);
    }

    @DataProvider
    public Object[][] noProxyMatches() {
        return new Object[][]{
                // entry, host, bypass expected
                {"example.com", "example.com", true},
                {"example.com", "api.example.com", true},
                {"example.com", "notexample.com", false},
                {".example.com", "api.example.com", true},
                {".example.com", "example.com", true},
                {"*.example.com", "api.example.com", true},
                {"*.example.com", "evil-example.com", false},
                {"*", TENANT, true},
                {"localhost,127.0.0.1,.internal.corp", TENANT, false},
                {"localhost, .CYBERARK.cloud", TENANT, true},
                {"10.0.0.0/8", TENANT, false},
                {"none", TENANT, false},
        };
    }

    @Test(dataProvider = "noProxyMatches")
    public void noProxyRulesFollowTheUsualConventions(String entries, String host, boolean bypass) {
        ProxySettings proxy = ProxySettings.parse("http://proxy:8080", entries);

        assertEquals(proxy.bypasses(host), bypass, "noProxyHosts=" + entries + " host=" + host);
        assertEquals(proxy.appliesTo(host), !bypass);
    }

    @Test
    public void toStringShowsOnlyHostAndPort() {
        assertEquals(ProxySettings.parse("http://proxy.corp.example:8080/path", "none").toString(),
                "http://proxy.corp.example:8080");
    }
}
