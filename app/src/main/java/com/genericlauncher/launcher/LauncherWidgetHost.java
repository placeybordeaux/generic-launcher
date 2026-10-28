package com.genericlauncher.launcher;

import android.appwidget.AppWidgetHost;
import android.content.Context;

/**
 * AppWidgetHost for the launcher home screen. HOST_ID 1024 avoids conflicts with
 * the system widget host and other app hosts.
 */
class LauncherWidgetHost extends AppWidgetHost {

    static final int HOST_ID = 1024;

    LauncherWidgetHost(Context context) {
        super(context, HOST_ID);
    }
}
