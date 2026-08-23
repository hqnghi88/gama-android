package com.gama.nativeapp;

import android.app.Application;
import android.content.Context;
import android.util.Log;

public class GamaApplication extends Application {
    private static final String TAG = "GamaApplication";

    private static volatile Context appContext;

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;
        // Note: NOT setting org.geotools.referencing.forceXY here - it triggers
        // LongitudeFirstFactory wrapping in DefaultAuthorityFactory which causes
        // RecursiveSearchException on Android. Without forceXY, CRS uses declared
        // axis order from the .prj file, which already uses (longitude, latitude).
        System.setProperty("use_global_preference_store", "false");
        System.setProperty("java.util.prefs.PreferencesFactory", "com.gama.nativeapp.NoOpPreferencesFactory");
    }
}
