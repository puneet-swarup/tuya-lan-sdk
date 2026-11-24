package com.tuyalan.device;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.tuyalan.devices.TuyaDeviceOptions;
import com.tuyalan.devices.TuyaLocalDevice;
import com.tuyalan.devices.TuyaStatus;
import com.tuyalan.protocol.TuyaProtocolVersion;
import com.tuyalan.TuyaException;
import java.util.Collections;
import org.junit.Test;

/**
 * Proves the core API is usable from Java with nothing bolted on: no facade, no
 * Continuation, no coroutine builder. If this stops compiling, the library has
 * stopped being a Java library.
 *
 * No socket is opened - a device that cannot be reached is the normal state in
 * CI, and connecting is not what this test is about.
 */
public class JavaApiTest {

    /**
     * Built with setters rather than the full constructor: a Kotlin data class
     * with defaults has no Java-friendly constructor, which is exactly the kind
     * of thing a Java consumer trips over.
     */
    private static TuyaDeviceOptions options() {
        return new TuyaDeviceOptionsBuilder()
                .host("192.168.1.42")
                .deviceId("bf0123456789abcdefghij")
                .localKey("0123456789abcdef")
                .version(TuyaProtocolVersion.V33)
                .build();
    }

    @Test
    public void constructsAndReportsState() throws TuyaException {
        TuyaDevice device = new TuyaLocalDevice(options());
        assertFalse("a device that was never connected is not connected", device.isConnected());
        device.close();
    }

    @Test
    public void registersListenersAsLambdas() throws TuyaException {
        TuyaDevice device = new TuyaLocalDevice(options());

        // A Java lambda against a Kotlin `fun interface` - this is the whole
        // point of the listener design.
        TuyaStatusListener onStatus = status -> { };
        TuyaErrorListener onError = error -> { };
        TuyaDisconnectListener onDisconnect = cause -> { };

        device.addStatusListener(onStatus);
        device.addErrorListener(onError);
        device.addDisconnectListener(onDisconnect);

        device.removeStatusListener(onStatus);
        device.removeErrorListener(onError);
        device.removeDisconnectListener(onDisconnect);

        device.close();
    }

    @Test
    public void blockingCallsAreReachableFromJava() throws TuyaException {
        TuyaDevice device = new TuyaLocalDevice(options());
        try {
            device.connect();
        } catch (TuyaException unreachable) {
            // Expected: nothing listens at 192.168.1.42. What matters is that
            // Java could make the call without a coroutine.
        } finally {
            device.close();
        }
    }

    @Test
    public void isUsableInTryWithResources() throws TuyaException {
        try (TuyaDevice device = new TuyaLocalDevice(options())) {
            assertNotNull(device);
            assertFalse(device.isConnected());
        }
    }

    @Test
    public void typedDataPointsAreWritable() throws TuyaException {
        TuyaDevice device = new TuyaLocalDevice(options());
        try {
            device.setDp("1", true);
        } catch (TuyaException unreachable) {
            // expected: not connected
        } finally {
            device.close();
        }
    }
}
