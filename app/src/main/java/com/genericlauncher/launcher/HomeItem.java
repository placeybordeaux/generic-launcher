package com.genericlauncher.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A single occupant on the home screen: an app shortcut, a folder of apps, or an app widget.
 * Positions are in grid cells (0-based). Apps and folders are always 1x1; widgets may span
 * multiple cells. Mutation of position and folder contents is intentionally package-private so
 * only {@link HomeScreenStore} drives state changes.
 *
 * <p>Dock items (Phase B) use {@code screen = HomeItem.DOCK} (-1). Their {@code cellX} is the
 * dock slot index (0-based), {@code cellY} is always 0. Dock items are APP or FOLDER only.
 */
public final class HomeItem {

    /** Sentinel screen value for dock items — persisted as -1 in homescreen.json. */
    public static final int DOCK = -1;

    public enum Type { APP, FOLDER, WIDGET }

    final Type type;
    int screen;   // 0-based page index
    int cellX;
    int cellY;
    int spanX;
    int spanY;

    // APP
    final String packageName;

    // FOLDER
    String folderName;               // mutable — editable from the folder popup (Phase 2)
    final List<String> packageNames; // ordered; mutable list managed by HomeScreenStore

    // WIDGET
    final int appWidgetId;
    final String provider;           // ComponentName#flattenToString()

    private HomeItem(Type type, int screen, int cellX, int cellY, int spanX, int spanY,
                     String packageName, String folderName, List<String> packageNames,
                     int appWidgetId, String provider) {
        this.type = type;
        this.screen = screen;
        this.cellX = cellX;
        this.cellY = cellY;
        this.spanX = spanX;
        this.spanY = spanY;
        this.packageName = packageName;
        this.folderName = folderName;
        this.packageNames = packageNames != null ? new ArrayList<>(packageNames) : new ArrayList<>();
        this.appWidgetId = appWidgetId;
        this.provider = provider;
    }

    // --- Factory methods ---

    public static HomeItem app(int screen, int cellX, int cellY, String packageName) {
        return new HomeItem(Type.APP, screen, cellX, cellY, 1, 1,
                packageName, null, null, -1, null);
    }

    public static HomeItem folder(int screen, int cellX, int cellY,
                                   String folderName, List<String> packageNames) {
        return new HomeItem(Type.FOLDER, screen, cellX, cellY, 1, 1,
                null, folderName, packageNames, -1, null);
    }

    public static HomeItem widget(int screen, int cellX, int cellY,
                                   int spanX, int spanY, int appWidgetId, String provider) {
        return new HomeItem(Type.WIDGET, screen, cellX, cellY, spanX, spanY,
                null, null, null, appWidgetId, provider);
    }

    // --- Getters ---

    public Type getType() { return type; }
    public int getScreen() { return screen; }
    public int getCellX() { return cellX; }
    public int getCellY() { return cellY; }
    public int getSpanX() { return spanX; }
    public int getSpanY() { return spanY; }

    /** APP only. */
    public String getPackageName() { return packageName; }

    /** FOLDER only. */
    public String getFolderName() { return folderName; }
    /** FOLDER only — call through the store so saves are triggered. */
    void setFolderName(String name) { folderName = name; }

    /**
     * FOLDER only — live list; do not modify directly. Use {@link HomeScreenStore} mutators
     * so changes are persisted.
     */
    public List<String> getPackageNames() { return packageNames; }

    /** WIDGET only. */
    public int getAppWidgetId() { return appWidgetId; }
    /** WIDGET only — ComponentName#flattenToString(). */
    public String getProvider() { return provider; }

    // --- JSON serialization (org.json, already on the Android platform) ---

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type.name());
        o.put("screen", screen);
        o.put("cellX", cellX);
        o.put("cellY", cellY);
        o.put("spanX", spanX);
        o.put("spanY", spanY);
        switch (type) {
            case APP:
                o.put("packageName", packageName);
                break;
            case FOLDER:
                o.put("folderName", folderName != null ? folderName : "");
                JSONArray arr = new JSONArray();
                for (String p : packageNames) arr.put(p);
                o.put("packageNames", arr);
                break;
            case WIDGET:
                o.put("appWidgetId", appWidgetId);
                o.put("provider", provider != null ? provider : "");
                break;
        }
        return o;
    }

    public static HomeItem fromJson(JSONObject o) throws JSONException {
        Type type = Type.valueOf(o.getString("type"));
        int screen = o.getInt("screen");
        int cellX  = o.getInt("cellX");
        int cellY  = o.getInt("cellY");
        int spanX  = o.getInt("spanX");
        int spanY  = o.getInt("spanY");
        switch (type) {
            case APP:
                return app(screen, cellX, cellY, o.getString("packageName"));
            case FOLDER: {
                JSONArray arr = o.getJSONArray("packageNames");
                List<String> pkgs = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) pkgs.add(arr.getString(i));
                return folder(screen, cellX, cellY, o.getString("folderName"), pkgs);
            }
            case WIDGET:
                return widget(screen, cellX, cellY, spanX, spanY,
                        o.getInt("appWidgetId"), o.getString("provider"));
            default:
                throw new JSONException("Unknown type: " + type);
        }
    }
}
