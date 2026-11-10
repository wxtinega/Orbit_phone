package com.orbitdial.app;

import android.app.Activity;
import android.os.Bundle;

/** Shared behaviour: theme, glass window, redraw after a theme change, call permission flow. */
abstract class BaseActivity extends Activity {
    private int themeVersion;
    private boolean darkAtStart;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.load(this);
        themeVersion = Prefs.themeVersion(this);
        darkAtStart = Ui.dark;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.load(this);
        if (Prefs.themeVersion(this) != themeVersion || Ui.dark != darkAtStart) {
            recreate();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        Actions.onPermissionResult(this, code, results);
    }

    int dp(float v) {
        return Ui.dp(this, v);
    }

    void snack(String msg) {
        Ui.snack(this, msg, null, null);
    }
}
