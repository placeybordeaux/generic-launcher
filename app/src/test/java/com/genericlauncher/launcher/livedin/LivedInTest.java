package com.genericlauncher.launcher.livedin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import androidx.test.core.app.ApplicationProvider;

import com.genericlauncher.launcher.AppInfo;
import com.genericlauncher.launcher.personality.Personality;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

import static org.junit.Assert.*;

/** The Lived-in treatment as the launcher drives it: wrap, launch, leave, come back. */
@RunWith(RobolectricTestRunner.class)
public class LivedInTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        LivedIn.resetForTests();
    }

    @After
    public void tearDown() {
        LivedIn.resetForTests();
    }

    private LivedInDrawable treat(String key) {
        Drawable d = LivedIn.TREATMENT.treat(ctx, new ColorDrawable(Color.BLUE), key);
        assertTrue(d instanceof LivedInDrawable);
        d.setBounds(0, 0, 96, 96);
        return (LivedInDrawable) d;
    }

    private static void draw(Drawable d) {
        Bitmap bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(bmp));
    }

    @Test
    public void thisBranchWiresLivedInIntoThePersonality() {
        assertSame(LivedIn.TREATMENT, Personality.ICON);
    }

    @Test
    public void nullIconStaysNull() {
        assertNull(LivedIn.TREATMENT.treat(ctx, null, "com.a"));
    }

    @Test
    public void defaultsToFullLivedIn() {
        assertEquals(Wear.Variant.FULL, LivedIn.getVariant(ctx));
        assertEquals(Wear.Style.BRASS, LivedIn.getStyle(ctx));
    }

    @Test
    public void aFreshInstallDrawsEveryIconPristine() {
        LivedInDrawable d = treat("com.never.launched");
        draw(d);
        assertEquals(Wear.PRISTINE, d.lookForTests());
    }

    @Test
    public void launchesAreRecorded() {
        LivedIn.TREATMENT.onLaunched(ctx, "com.a");
        LivedIn.TREATMENT.onLaunched(ctx, "com.a");
        assertEquals(2, LaunchLog.get(ctx).count("com.a"));
    }

    @Test
    public void timelapseAgesIconsOnScreenWithoutRebuildingThem() {
        LivedInDrawable[] ds = new LivedInDrawable[30];
        for (int i = 0; i < ds.length; i++) {
            ds[i] = treat("com.app" + i);
            draw(ds[i]);
            assertEquals(Wear.PRISTINE, ds[i].lookForTests());
        }

        LivedIn.setVariant(ctx, Wear.Variant.TIMELAPSE);

        int aged = 0;
        for (LivedInDrawable d : ds) {
            draw(d); // renders the look into a bitmap; must not throw
            if (d.lookForTests() != Wear.PRISTINE) aged++;
        }
        assertTrue("time-lapse should age most icons at once, aged " + aged, aged > 20);
    }

    @Test
    public void offDrawsEverythingPristine() {
        LivedIn.setVariant(ctx, Wear.Variant.TIMELAPSE);
        LivedIn.setVariant(ctx, Wear.Variant.OFF);
        for (int i = 0; i < 20; i++) {
            LivedInDrawable d = treat("com.app" + i);
            draw(d);
            assertEquals(Wear.PRISTINE, d.lookForTests());
        }
    }

    @Test
    public void everyLookAndStyleRenders() {
        Drawable base = new ColorDrawable(Color.RED);
        for (Wear.Style style : Wear.Style.values()) {
            for (int p = 0; p <= 3; p++) {
                for (int dust = 0; dust <= 3; dust++) {
                    for (int web = 0; web <= 2; web++) {
                        Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                        WearPainter.render(bmp, base, Wear.pack(p, dust, web), style, 42);
                    }
                }
            }
        }
    }

    @Test
    public void renderingDoesNotDisturbTheSharedIconsBounds() {
        Drawable base = new ColorDrawable(Color.RED);
        base.setBounds(3, 4, 50, 60);
        Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        WearPainter.render(bmp, base, Wear.pack(2, 2, 1), Wear.Style.BRASS, 1);
        assertEquals(3, base.getBounds().left);
        assertEquals(60, base.getBounds().bottom);
    }

    @Test
    public void theDustPuffWaitsForTheLauncherToComeBack() {
        LivedIn.setVariant(ctx, Wear.Variant.TIMELAPSE);
        String key = dustyTimelapseKey();
        LivedInDrawable d = treat(key);
        LivedIn.TREATMENT.onResume(ctx);
        draw(d);
        assertTrue(Wear.dustOf(d.lookForTests()) > 0);

        // The tap: the icon is now clean, but the launched app is opening over the launcher,
        // so the puff must not start yet.
        LivedIn.TREATMENT.onLaunched(ctx, key);
        draw(d);
        assertFalse(d.puffRunningForTests());

        // Away, then back: now it plays.
        LivedIn.TREATMENT.onPause();
        LivedIn.TREATMENT.onResume(ctx);
        draw(d);
        assertTrue(d.puffRunningForTests());
        assertEquals(0, Wear.dustOf(d.lookForTests()));
    }

    @Test
    public void dataSinkRecordsFactsAndToleratesUnknownPackages() {
        AppInfo ghost = new AppInfo("Ghost", "com.not.installed", new ColorDrawable(0));
        ghost.setUsageScore(5000);
        LivedIn.DATA.onAppsLoaded(ctx, Collections.singletonList(ghost));
        // No such package: skipped rather than thrown — this runs on the loader's thread.
        assertNull(LivedIn.APP_FACTS.get("com.not.installed"));

        AppInfo self = new AppInfo("Me", ctx.getPackageName(), new ColorDrawable(0));
        self.setUsageScore(7000);
        LivedIn.DATA.onAppsLoaded(ctx, Collections.singletonList(self));
        long[] facts = LivedIn.APP_FACTS.get(ctx.getPackageName());
        assertNotNull(facts);
        assertEquals(7000, facts[1]);
    }

    @Test
    public void forgetUsageClearsTheLog() {
        LivedIn.TREATMENT.onLaunched(ctx, "com.a");
        LivedIn.forgetUsage(ctx);
        assertEquals(0, LaunchLog.get(ctx).count("com.a"));
    }

    private static String dustyTimelapseKey() {
        for (int i = 0; i < 1000; i++) {
            String key = "com.dusty" + i;
            if (Wear.fakeIdleMs(Wear.mix(key.hashCode())) >= 25 * Wear.DAY) return key;
        }
        throw new AssertionError("no dusty key in 1000 tries");
    }
}
