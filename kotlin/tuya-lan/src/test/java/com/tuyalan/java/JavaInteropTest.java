package com.tuyalan.java;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Proves the library is usable from Java: every call here is plain Java against
 * Kotlin code, with no Continuation, no coroutine builder and no Kotlin-only
 * syntax. If this stops compiling, Java support has broken.
 *
 * Nothing here opens a socket - a device that cannot be reached is the normal
 * state in CI, and connecting is not what this test is about.
 */
public class JavaInteropTest {

    @Test
    public void constructsThroughTheFactory() {
        TuyaLanDevice device = TuyaLanDevice.create(
                "192.168.1.42", "bf1234567890abcdefghij", "0123456789abcdef");
        assertNotNull(device);
        assertFalse("a device that was never connected is not connected", device.isConnected());
        device.close();
    }

    @Test
    public void acceptsEveryProtocolVersion() {
        String[] versions = {"3.3", "3.4", "3.5", "not-a-version"};
        for (String version : versions) {
            TuyaLanDevice device = TuyaLanDevice.create(
                    "192.168.1.42", "bf1234567890abcdefghij", "0123456789abcdef", version, 6668);
            assertNotNull("version " + version, device);
            device.close();
        }
    }

    @Test
    public void exposesTheFullConstructor() {
        TuyaLanDevice device = new TuyaLanDevice(
                "192.168.1.42", "bf1234567890abcdefghij", "0123456789abcdef",
                "3.5", 6668, 5000, 5000L);
        assertFalse(device.isConnected());
        device.close();
    }

    @Test
    public void isUsableInTryWithResources() {
        try (TuyaLanDevice device = TuyaLanDevice.create(
                "192.168.1.42", "bf1234567890abcdefghij", "0123456789abcdef")) {
            assertFalse(device.isConnected());
        }
        assertFalse("closed devices report closed", device.isConnected());
    }
}
