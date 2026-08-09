package org.eclipse.core.internal.preferences;

import java.io.InputStream;

public class PreferencesService {
    private static final PreferencesService INSTANCE = new PreferencesService();

    public static PreferencesService getDefault() { return INSTANCE; }

    public void importPreferences(InputStream is) throws Exception {
        if (is != null) is.close();
    }
}
