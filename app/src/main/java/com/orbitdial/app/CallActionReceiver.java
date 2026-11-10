package com.orbitdial.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.TelecomManager;

/** Buttons on call notifications, and the system's "show missed calls" request. */
public class CallActionReceiver extends BroadcastReceiver {
    static final String DECLINE = "com.orbitdial.app.DECLINE";
    static final String HANG_UP = "com.orbitdial.app.HANG_UP";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case DECLINE:
                CallManager.decline(CallManager.ringing());
                break;
            case HANG_UP:
                CallManager.hangUp(CallManager.primary());
                break;
            case TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION: {
                int count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0);
                String number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER);
                PendingResult pr = goAsync();
                Data.IO.execute(() -> {
                    try {
                        Ui.load(c);
                        CallNotifier.missed(c, number, count);
                    } finally {
                        pr.finish();
                    }
                });
                break;
            }
            default:
                break;
        }
    }
}
