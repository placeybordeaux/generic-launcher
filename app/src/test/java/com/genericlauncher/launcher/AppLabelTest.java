package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Every variant is tellable apart by name in the app drawer, the home-app picker and the
 * notification-access list.
 *
 * <p>This is a launcher, so the installs coexist by design: a fork develops against a debug build
 * while a release build holds the HOME role, and the whole point of the {@code color} flavour is to
 * sit beside {@code plain} in one screenshot. Two of them sharing a name is not cosmetic — picking
 * the wrong "Launcher Debug" in the home-app picker hands the HOME role to the wrong APK, and the
 * notification-access list is a column of identical rows.
 *
 * <p>It is guarded here because the way it breaks is silent. The label is assembled from manifest
 * placeholders, and a build type's {@code manifestPlaceholders} map is merged into the flavour's
 * <em>key by key, with the build type winning</em>. One key set by both — the obvious way to write
 * it — therefore compiles, installs and runs while quietly discarding the flavour's half, and
 * nothing says so until two identically-named icons are sitting in the drawer. Hence the split into
 * {@code appName} (the flavour's) and {@code buildTag} (the build type's), which cannot collide.
 *
 * <p>Unit tests build the {@code debug} variant, so that is the build type asserted below.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AppLabelTest {

    /** The base name every variant's label is built from. */
    private static final String BASE = "Launcher";
    /** The debug build type's contribution — see app/build.gradle's `buildTag`. */
    private static final String BUILD_TAG = " Debug";
    /** The flavour that carries no identity of its own; see PlainPersonalityTest. */
    private static final String DEFAULT_FLAVOUR = "plain";

    private String label() {
        Context ctx = ApplicationProvider.getApplicationContext();
        return ctx.getApplicationInfo().loadLabel(ctx.getPackageManager()).toString();
    }

    @Test
    public void labelCarriesTheBuildType() {
        String label = label();
        assertTrue("label '" + label + "' should start with '" + BASE + "'",
                label.startsWith(BASE));
        assertTrue("label '" + label + "' should end with '" + BUILD_TAG + "' so a debug install is"
                        + " never mistaken for the release one holding the HOME role",
                label.endsWith(BUILD_TAG));
    }

    /**
     * Whatever sits between the base name and the build tag is the flavour's own contribution.
     * The default flavour deliberately adds nothing; every other flavour must add something, or
     * it is indistinguishable from the default at the same build type.
     */
    @Test
    public void labelCarriesTheFlavour() {
        String label = label();
        String flavourPart =
                label.substring(BASE.length(), label.length() - BUILD_TAG.length()).trim();

        if (DEFAULT_FLAVOUR.equals(BuildConfig.FLAVOR)) {
            assertEquals("the taste-free default contributes no name of its own",
                    "", flavourPart);
        } else {
            assertFalse("flavour '" + BuildConfig.FLAVOR + "' contributes no name, so it installs"
                            + " under the same label as the default build. Set `appName` in the"
                            + " flavour — and do not set `appLabel`-style single keys in a build"
                            + " type, which silently overwrite the flavour's value.",
                    flavourPart.isEmpty());
        }
    }
}
