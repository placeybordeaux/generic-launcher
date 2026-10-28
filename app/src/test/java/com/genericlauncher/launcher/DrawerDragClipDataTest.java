package com.genericlauncher.launcher;

import android.content.ClipData;
import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowView;

import java.util.Collections;
import com.genericlauncher.launcher.personality.DrawerSection;

import static org.junit.Assert.*;

/**
 * What the drawer's long-press drag advertises to the system.
 *
 * <p>The assertion that matters is the negative one: the drag must start with a <b>null</b>
 * ClipData. A drag advertising {@code text/plain} makes OEM "smart drag" panels (OPPO/OnePlus
 * File Dock) register a system drop target over the top-right of the screen, which swallows
 * DRAG_LOCATION and the drop — the same bug that made the Uninstall pill unhittable and that
 * {@code HomescreenView.startItemDrag} already works around. Nothing reads the clip: the package
 * key travels in the {@link DrawerDragState} localState, which is what
 * {@code handleIncomingAppDrop} consumes. This test exists so nobody "helpfully" puts a
 * ClipData back.
 *
 * <p>Mechanics: {@code View.startDragAndDrop} is final, so it cannot be captured by subclassing —
 * hence the shadow below. The shadow is registered for {@link View} itself, so the drag is fired
 * at a bare View rather than the inflated row (a LinearLayout, which keeps Robolectric's own
 * shadow). That is faithful to the code under test, which drags whichever view the long-press
 * listener is handed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = DrawerDragClipDataTest.ShadowDragRecordingView.class)
public class DrawerDragClipDataTest {

    /** Records the drag arguments instead of asking a nonexistent window to start one. */
    @Implements(View.class)
    public static class ShadowDragRecordingView extends ShadowView {
        static boolean started;
        static ClipData clip;
        static Object localState;
        static int flags;

        @Implementation
        protected boolean startDragAndDrop(ClipData data, View.DragShadowBuilder shadow,
                                           Object myLocalState, int dragFlags) {
            started    = true;
            clip       = data;
            localState = myLocalState;
            flags      = dragFlags;
            return true;
        }

        static void clearRecording() {
            started    = false;
            clip       = null;
            localState = null;
            flags      = -1;
        }
    }

    private Context ctx;
    private View.OnLongClickListener longPress;

    @Before
    public void setUp() {
        ShadowDragRecordingView.clearRecording();
        ctx = ApplicationProvider.getApplicationContext();
        longPress = longPressListenerForApp("com.example.app");
    }

    @After
    public void tearDown() {
        ShadowDragRecordingView.clearRecording();
    }

    /** Binds one app row through the adapter and hands back the long-press listener it installed. */
    private View.OnLongClickListener longPressListenerForApp(String pkg) {
        AppAdapter adapter = new AppAdapter(ctx);
        // One headerless section, so position 0 is the app rather than a section title.
        adapter.setSections(Collections.singletonList(new DrawerSection("",
                Collections.singletonList(new AppInfo("Label", pkg, /*icon=*/ null)))));

        RecyclerView rv = new RecyclerView(ctx);
        // item_app's root gets its LayoutParams from the RecyclerView, which refuses to generate
        // them without a layout manager — inflation throws otherwise.
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        RecyclerView.ViewHolder holder =
                adapter.onCreateViewHolder(rv, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);

        ShadowView shadow = Shadow.extract(holder.itemView);
        View.OnLongClickListener l = shadow.getOnLongClickListener();
        assertNotNull("adapter installed no long-press listener on the app row", l);
        return l;
    }

    /** Fires the adapter's long-press handler at a view whose drag we can observe. */
    private void fireLongPress(View.OnLongClickListener l) {
        assertTrue("long-press was not consumed", l.onLongClick(new View(ctx)));
    }

    @Test
    public void dragStartsWithNoClipData() {
        fireLongPress(longPress);

        assertTrue("no drag was started", ShadowDragRecordingView.started);
        assertNull("drawer drag must advertise no ClipData — see class doc",
                ShadowDragRecordingView.clip);
    }

    @Test
    public void packageKeyTravelsInTheLocalState() {
        // The counterpart to the null clip: dropping the ClipData must not lose the package,
        // because localState is the only channel handleIncomingAppDrop reads.
        fireLongPress(longPress);

        assertTrue(ShadowDragRecordingView.localState instanceof DrawerDragState);
        assertEquals("com.example.app",
                ((DrawerDragState) ShadowDragRecordingView.localState).packageKey);
    }

    @Test
    public void workProfileKeyIsCarriedThroughVerbatim() {
        // UserKeys composite keys must survive the drag intact — HomescreenView reduces them.
        fireLongPress(longPressListenerForApp("com.example.app#11"));

        assertNull(ShadowDragRecordingView.clip);
        assertEquals("com.example.app#11",
                ((DrawerDragState) ShadowDragRecordingView.localState).packageKey);
    }

    @Test
    public void dragIsProcessLocal() {
        // flags 0 — no DRAG_FLAG_GLOBAL, so the drag can never reach another app.
        fireLongPress(longPress);
        assertEquals(0, ShadowDragRecordingView.flags);
    }
}
