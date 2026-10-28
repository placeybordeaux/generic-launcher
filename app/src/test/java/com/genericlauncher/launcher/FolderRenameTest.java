package com.genericlauncher.launcher;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Renaming a folder in its popup has to show up on the home-screen chip as soon as the popup
 * closes.
 *
 * <p>The bug: {@code closeFolderPopup()} committed the edit through
 * {@link HomeScreenStore#renameFolder} — which mutates the live {@link HomeItem} and persists
 * it — but then only posted a {@code refresh()} when a member had been *reordered*. The chip's
 * label {@code TextView} is bound once, in {@code buildFolderView}, from
 * {@code item.getFolderName()}. So the model and the JSON both said "Games" while the screen
 * kept saying "Folder" until something unrelated (an app reload, a settings change, a restart)
 * happened to rebuild the page.
 *
 * <p>The last two tests pin the other half of the fix: the rebuild is conditional. Opening a
 * folder and dismissing it without typing, or clearing the field on a folder that is already
 * called "Folder", must not rebuild the pages — every widget on the home screen re-inflates its
 * RemoteViews on a refresh, which is visible as a flicker.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderRenameTest {

    private static final int W = 1080;
    private static final int H = 2424;

    private ActivityController<Activity> controller;
    private Activity        activity;
    private FrameLayout     root;
    private HomeScreenStore store;
    private HomescreenView  view;
    private HomeItem        folder;
    private Map<String, AppInfo> apps;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(activity);

        apps = new HashMap<>();
        apps.put("com.example.first",
                new AppInfo("com.example.first", "First", null));
        apps.put("com.example.second",
                new AppInfo("com.example.second", "Second", null));

        folder = HomeItem.folder(0, 0, 0, "Folder",
                Arrays.asList("com.example.first", "com.example.second"));
        store.replaceAll(new ArrayList<>(Arrays.asList(folder)));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(apps);
        root.addView(view, W, H);
        view.refresh(apps);
        layoutPass();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    private void layoutPass() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /** The label under page 0's folder chip — the thing the user actually reads. */
    private TextView homeChipLabel() {
        LinearLayout cell = (LinearLayout) view.getPage(0).getChildAt(0);
        for (int i = 0; i < cell.getChildCount(); i++) {
            if (cell.getChildAt(i) instanceof TextView) return (TextView) cell.getChildAt(i);
        }
        fail("test setup: the folder chip carries no label");
        return null;
    }

    /** The name EditText inside the open popup (card is overlay child 1; scrim is child 0). */
    private EditText popupNameEdit() {
        View overlay = view.getChildAt(view.getChildCount() - 1);
        assertTrue("test setup: no popup overlay on top", overlay instanceof FrameLayout);
        ViewGroup card = (ViewGroup) ((FrameLayout) overlay).getChildAt(1);
        assertTrue("test setup: the card's first child should be the name field",
                card.getChildAt(0) instanceof EditText);
        return (EditText) card.getChildAt(0);
    }

    /** Opens the popup, types {@code typed} into the name field, and closes it again. */
    private void renameViaPopup(String typed) {
        view.openFolderPopup(folder);
        popupNameEdit().setText(typed);
        view.closeFolderPopup();
        ShadowLooper.idleMainLooper(); // the rebuild is posted, not inline
        layoutPass();
    }

    // ---------------------------------------------------------------------
    // The bug
    // ---------------------------------------------------------------------

    @Test
    public void renamingAFolderUpdatesTheHomeScreenLabelWithoutAReload() {
        assertEquals("test setup", "Folder", homeChipLabel().getText().toString());

        renameViaPopup("Games");

        assertEquals("the chip label must show the new name as soon as the popup closes",
                "Games", homeChipLabel().getText().toString());
    }

    @Test
    public void renamingAFolderAlsoUpdatesTheModel() {
        renameViaPopup("Games");

        assertEquals("Games", folder.getFolderName());
        assertEquals("Games", store.getItems().get(0).getFolderName());
    }

    @Test
    public void reopeningTheFolderShowsTheNewNameInTheNameField() {
        renameViaPopup("Games");

        view.openFolderPopup(folder);
        assertEquals("Games", popupNameEdit().getText().toString());
        view.closeFolderPopup();
    }

    @Test
    public void surroundingWhitespaceIsTrimmedBeforeItReachesTheLabel() {
        renameViaPopup("  Games  ");

        assertEquals("Games", homeChipLabel().getText().toString());
        assertEquals("Games", folder.getFolderName());
    }

    @Test
    public void clearingTheNameFallsBackToTheDefaultLabel() {
        renameViaPopup("Games");
        assertEquals("test setup", "Games", homeChipLabel().getText().toString());

        renameViaPopup("");

        assertEquals("Folder", homeChipLabel().getText().toString());
        assertEquals("Folder", folder.getFolderName());
    }

    // ---------------------------------------------------------------------
    // ...and the rebuild stays conditional
    // ---------------------------------------------------------------------

    @Test
    public void dismissingThePopupWithoutEditingRebuildsNothing() {
        View pageBefore = view.getPage(0);

        view.openFolderPopup(folder);
        view.closeFolderPopup();
        ShadowLooper.idleMainLooper();

        assertSame("an unchanged name must not cost a full page rebuild",
                pageBefore, view.getPage(0));
    }

    @Test
    public void clearingTheNameOfAnAlreadyDefaultFolderRebuildsNothing() {
        View pageBefore = view.getPage(0);

        // "" is stored as "Folder", which is what this folder is already called — so this is a
        // no-op, not a rename, even though the typed text differs from the stored name.
        view.openFolderPopup(folder);
        popupNameEdit().setText("");
        view.closeFolderPopup();
        ShadowLooper.idleMainLooper();

        assertSame(pageBefore, view.getPage(0));
        assertEquals("Folder", folder.getFolderName());
    }

    // ---------------------------------------------------------------------
    // Enter commits
    // ---------------------------------------------------------------------

    /**
     * Enter used to be the field's default NEXT action: focus hopped to the first member cell,
     * the keyboard stayed up and the name stayed uncommitted. It now stores the name and puts
     * the keyboard away while the popup stays open.
     */
    @Test
    public void enterInTheNameFieldCommitsTheNameAndHidesTheKeyboard() {
        view.openFolderPopup(folder);
        EditText edit = popupNameEdit();
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(edit, 0);
        assertTrue("test setup: the keyboard must be up to be put away",
                Shadows.shadowOf(imm).isSoftInputVisible());

        edit.setText("Games");
        edit.onEditorAction(EditorInfo.IME_ACTION_DONE);

        assertEquals("Games", folder.getFolderName());
        assertFalse(Shadows.shadowOf(imm).isSoftInputVisible());
        assertTrue("Enter commits; it does not close the popup", view.isFolderPopupOpen());
    }

    /**
     * After Enter the store already holds the typed name, so the close-time comparison sees no
     * change — the chip rebuild must still happen or the label under the popup goes stale in
     * exactly the way the class-level comment describes.
     */
    @Test
    public void aNameCommittedWithEnterStillReachesTheChipOnClose() {
        view.openFolderPopup(folder);
        EditText edit = popupNameEdit();
        edit.setText("Games");
        edit.onEditorAction(EditorInfo.IME_ACTION_DONE);

        view.closeFolderPopup();
        ShadowLooper.idleMainLooper();
        layoutPass();

        assertEquals("Games", homeChipLabel().getText().toString());
    }
}
