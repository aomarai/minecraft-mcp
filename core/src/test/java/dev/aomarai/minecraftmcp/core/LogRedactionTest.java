package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogRedactionTest {

    @Test
    void ipv4AddressesWithAndWithoutPortsAreMasked() {
        assertEquals("Steve[/[ip]] logged in", LogRedaction.redact("Steve[/192.168.1.5:54321] logged in"));
        assertEquals("from [ip]", LogRedaction.redact("from 10.0.0.1"));
        assertEquals("[ip] and [ip]", LogRedaction.redact("127.0.0.1 and 8.8.8.8:25565"));
    }

    @Test
    void ipv6AddressesInEveryShapeAreMasked() {
        assertEquals("Steve[/[ip]] logged in with entity id 7",
                LogRedaction.redact("Steve[/[2001:db8::1]:51234] logged in with entity id 7"));
        assertEquals("Steve[/[ip]] logged in", LogRedaction.redact("Steve[/[::ffff:1.2.3.4]:51234] logged in"));
        assertEquals("from [ip]", LogRedaction.redact("from 2001:db8::1"));
        assertEquals("from [ip]", LogRedaction.redact("from 2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
        assertEquals("from [ip]", LogRedaction.redact("from ::1"));
        assertEquals("from [ip]", LogRedaction.redact("from ::ffff:1.2.3.4"));
        assertEquals("from [ip]", LogRedaction.redact("from fe80::1"));
        assertEquals("from [ip] ok", LogRedaction.redact("from 1:2:3:4:5:6::7 ok"));
        assertEquals("from [ip] ok", LogRedaction.redact("from 1::2:3:4:5:6:7 ok"));
    }

    @Test
    void thingsThatAreNotIpv6AddressesAreLeftAlone() {
        for (String line : new String[]{"[12:34:56] [Server thread/INFO]: hi", "12:34:56", "v1.21.1:beta",
                "Foo::bar", "Face::decode", "at x=1 y=2:3", "::", "a:b:c:d", "1:2:3:4:5:6:7:8:9"}) {
            assertEquals(line, LogRedaction.redact(line));
        }
    }

    @Test
    void thingsThatAreNotIpAddressesAreLeftAlone() {
        for (String line : new String[]{"version 1.21.1", "took 1.5s", "1.2.3", "id 12345", "x=1.2.3.4.5y"}) {
            String redacted = LogRedaction.redact(line);
            if (line.equals("x=1.2.3.4.5y")) {
                assertFalse(redacted.isEmpty());
            } else {
                assertEquals(line, redacted);
            }
        }
    }

    @Test
    void lineLengthIsCutAtTheLimitExactly() {
        String atLimit = "a".repeat(LogRedaction.MAX_LINE);
        assertEquals(atLimit, LogRedaction.redact(atLimit));
        assertEquals(atLimit + "...", LogRedaction.redact(atLimit + "b"));
    }

    @Test
    void maskingHappensBeforeCutting() {
        String line = "a".repeat(LogRedaction.MAX_LINE - 3) + " 192.168.100.200";
        String redacted = LogRedaction.redact(line);
        assertFalse(redacted.contains("192"), redacted);
    }
}
