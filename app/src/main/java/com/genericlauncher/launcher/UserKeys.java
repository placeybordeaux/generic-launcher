package com.genericlauncher.launcher;

/**
 * Composite home-screen identity keys distinguishing work-profile apps from personal ones.
 *
 * Key scheme:
 *   personal:  bare package name               e.g. "com.example.app"
 *   work:      "com.example.app#&lt;userSerial&gt;"   e.g. "com.example.app#10"
 *
 * The primary user's serial number is always 0 on Android (stable platform guarantee), so
 * {@code compose(pkg, 0)} always returns the bare key. Work profiles receive serials &gt; 0.
 *
 * ColorCache always keys by bare package name for both personal and work apps: work-profile
 * icons use the same artwork (just badged), so sharing one color-cache entry per package
 * avoids redundant palette analysis.
 */
public final class UserKeys {

    /** Separator between package name and user serial in composite keys. */
    static final char SEPARATOR = '#';

    private UserKeys() {}

    /**
     * Returns the key for a package on the given user.
     *
     * @param pkg    package name (e.g. "com.example.app")
     * @param serial from {@code UserManager.getSerialNumberForUser}; 0 means primary/personal user
     * @return bare pkg for serial==0; "pkg#serial" otherwise
     */
    public static String compose(String pkg, long serial) {
        return serial == 0 ? pkg : pkg + SEPARATOR + serial;
    }

    /** Extracts the package name from a key (works for bare and composite keys). */
    public static String packageOf(String key) {
        int idx = key.indexOf(SEPARATOR);
        return idx < 0 ? key : key.substring(0, idx);
    }

    /**
     * Extracts the user serial from a key.
     *
     * @return 0 for a bare (personal) key, or the serial encoded after the separator
     */
    public static long serialOf(String key) {
        int idx = key.indexOf(SEPARATOR);
        if (idx < 0) return 0;
        try {
            return Long.parseLong(key.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True if this key refers to a work-profile app (contains the separator). */
    public static boolean isWork(String key) {
        return key.indexOf(SEPARATOR) >= 0;
    }
}
