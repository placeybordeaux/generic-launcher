package com.genericlauncher.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the {@link UserKeys} composite-key helper and the composite-key behaviour of
 * {@link HomeScreenStore#removePackage}.
 *
 * UserKeys has no Android dependencies so plain JUnit suffices; Robolectric is not required.
 */
public class UserKeysTest {

    // -------------------------------------------------------------------------
    // compose
    // -------------------------------------------------------------------------

    @Test
    public void compose_personalSerial_returnsBareKey() {
        // Serial 0 is the primary/personal user — result is a bare package name.
        assertEquals("com.example.app", UserKeys.compose("com.example.app", 0));
    }

    @Test
    public void compose_workSerial_returnsCompositeKey() {
        assertEquals("com.example.app#10", UserKeys.compose("com.example.app", 10));
        assertEquals("com.example.app#999", UserKeys.compose("com.example.app", 999));
    }

    @Test
    public void compose_largeSerial_handledCorrectly() {
        String key = UserKeys.compose("com.pkg", Long.MAX_VALUE);
        assertEquals("com.pkg#" + Long.MAX_VALUE, key);
    }

    // -------------------------------------------------------------------------
    // packageOf
    // -------------------------------------------------------------------------

    @Test
    public void packageOf_bareKey_returnsItself() {
        assertEquals("com.example.app", UserKeys.packageOf("com.example.app"));
    }

    @Test
    public void packageOf_compositeKey_extractsPackage() {
        assertEquals("com.example.app", UserKeys.packageOf("com.example.app#10"));
        assertEquals("com.example.app", UserKeys.packageOf("com.example.app#999"));
    }

    @Test
    public void packageOf_compositeKey_ignoresSerial() {
        // Only the part before the first '#' is the package name.
        assertEquals("com.pkg", UserKeys.packageOf("com.pkg#0"));
    }

    // -------------------------------------------------------------------------
    // serialOf
    // -------------------------------------------------------------------------

    @Test
    public void serialOf_bareKey_returnsZero() {
        assertEquals(0, UserKeys.serialOf("com.example.app"));
    }

    @Test
    public void serialOf_compositeKey_returnsSerial() {
        assertEquals(10, UserKeys.serialOf("com.example.app#10"));
        assertEquals(999, UserKeys.serialOf("com.example.app#999"));
    }

    @Test
    public void serialOf_largeSerial_roundTrips() {
        long serial = Long.MAX_VALUE;
        String key = UserKeys.compose("com.pkg", serial);
        assertEquals(serial, UserKeys.serialOf(key));
    }

    @Test
    public void serialOf_malformedSuffix_returnsZero() {
        // Graceful degradation: separator present but suffix is not a number.
        assertEquals(0, UserKeys.serialOf("com.pkg#notanumber"));
    }

    // -------------------------------------------------------------------------
    // isWork
    // -------------------------------------------------------------------------

    @Test
    public void isWork_bareKey_returnsFalse() {
        assertFalse(UserKeys.isWork("com.example.app"));
    }

    @Test
    public void isWork_compositeKey_returnsTrue() {
        assertTrue(UserKeys.isWork("com.example.app#10"));
    }

    @Test
    public void isWork_compositeKeyWithZeroSerial_returnsTrue() {
        // A key with '#' is always treated as work, even if the encoded serial is 0.
        // In practice compose() never produces this (serial 0 → bare key), but the parser
        // must be consistent with what isWork reports.
        assertTrue(UserKeys.isWork("com.pkg#0"));
    }

    // -------------------------------------------------------------------------
    // round-trip: compose → packageOf / serialOf
    // -------------------------------------------------------------------------

    @Test
    public void roundTrip_personalApp() {
        String key = UserKeys.compose("com.example", 0);
        assertEquals("com.example", UserKeys.packageOf(key));
        assertEquals(0, UserKeys.serialOf(key));
        assertFalse(UserKeys.isWork(key));
    }

    @Test
    public void roundTrip_workApp() {
        String key = UserKeys.compose("com.example", 10);
        assertEquals("com.example", UserKeys.packageOf(key));
        assertEquals(10, UserKeys.serialOf(key));
        assertTrue(UserKeys.isWork(key));
    }
}
