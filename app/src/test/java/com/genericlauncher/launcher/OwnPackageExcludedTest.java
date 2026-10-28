package com.genericlauncher.launcher;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Process;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLauncherApps;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The launcher does not list itself in its own drawer.
 *
 * <p>It has a LAUNCHER activity like any app, so {@code getActivityList} returns it, and as the
 * HOME app it accrues more foreground time than anything else — so wherever usage access was
 * granted it topped the usage ordering, and tapping it just closed the drawer.
 * {@link LauncherActivityInfo} has no public constructor, so it is built reflectively, which is why this class pins {@code sdk = 33}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class OwnPackageExcludedTest {

    private static final String OTHER = "com.example.alpha";

    private Context ctx;
    private String ownPackage;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ownPackage = ctx.getPackageName();
        register(ownPackage, "Launcher");
        register(OTHER, "Alpha");
    }

    private static List<String> packagesOf(List<AppInfo> apps) {
        List<String> out = new ArrayList<>();
        for (AppInfo a : apps) out.add(a.getPackageName());
        Collections.sort(out);
        return out;
    }

    @Test
    public void theLauncherIsLeftOutOfTheFullLoad() {
        List<AppInfo> apps = AppLoader.loadApps(ctx, null);

        List<String> pkgs = packagesOf(apps);
        assertTrue("the fixture's other app must load, or the exclusion is untested: " + pkgs,
                pkgs.contains(OTHER));
        assertFalse("the launcher listed itself: " + pkgs, pkgs.contains(ownPackage));
    }

    /** The priority phase enumerates the same list and must not smuggle it in either. */
    @Test
    public void theLauncherIsLeftOutEvenWhenAskedForByKey() {
        List<AppInfo> apps = AppLoader.loadApps(ctx,
                new java.util.HashSet<>(java.util.Arrays.asList(ownPackage, OTHER)));

        assertEquals(Collections.singletonList(OTHER), packagesOf(apps));
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    private void register(String pkg, String label) {
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = pkg;
        activityInfo.name = pkg + ".MainActivity";
        // A non-localized label and a zero icon resource keep the fixture out of any APK's
        // Resources: loadLabel returns this string and loadIcon falls back to the framework's
        // default activity icon.
        activityInfo.nonLocalizedLabel = label;
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = pkg;
        applicationInfo.nonLocalizedLabel = label;
        activityInfo.applicationInfo = applicationInfo;

        LauncherApps launcherApps =
                (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        ShadowLauncherApps shadow = Shadow.extract(launcherApps);
        shadow.addEnabledPackage(Process.myUserHandle(), pkg);
        shadow.addActivity(Process.myUserHandle(), launcherActivityInfo(activityInfo));
    }

    private LauncherActivityInfo launcherActivityInfo(ActivityInfo activityInfo) {
        try {
            Class<?> statesClass = Class.forName("android.content.pm.IncrementalStatesInfo");
            Object states = ReflectionHelpers.callConstructor(statesClass,
                    ClassParameter.from(boolean.class, false),
                    ClassParameter.from(float.class, 1f));

            Class<?> internalClass =
                    Class.forName("android.content.pm.LauncherActivityInfoInternal");
            Object internal = ReflectionHelpers.callConstructor(internalClass,
                    ClassParameter.from(ActivityInfo.class, activityInfo),
                    ClassParameter.from(statesClass, states));

            UserHandle user = Process.myUserHandle();
            LauncherActivityInfo info = ReflectionHelpers.callConstructor(
                    LauncherActivityInfo.class,
                    ClassParameter.from(Context.class, ctx),
                    ClassParameter.from(UserHandle.class, user),
                    ClassParameter.from(internalClass, internal));
            assertNotNull(info);
            return info;
        } catch (ClassNotFoundException e) {
            throw new AssertionError("hidden LauncherActivityInfo internals moved", e);
        }
    }
}
