package com.orbitdial.app;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

/**
 * Android hands every call to this service once T.I Dial is the default phone app.
 * The phone itself still plays the ringtone (including per-contact ringtones);
 * this shows the call screen the moment the call arrives.
 */
public class CallService extends InCallService {

    @Override
    public void onCreate() {
        super.onCreate();
        CallManager.service = this;
        Ui.load(this);
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        CallManager.service = this;
        CallManager.add(this, call);
        int s = CallManager.state(call);
        boolean incoming = s == Call.STATE_RINGING;
        if (incoming) CallNotifier.showIncoming(this, call);
        // The system lets the phone app open its call screen straight away.
        try {
            Intent i = new Intent(this, InCallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            startActivity(i);
        } catch (RuntimeException ignored) {
            // The full-screen notification above takes over.
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        CallManager.remove(call);
        if (!CallManager.hasCalls()) {
            CallNotifier.cancelCall(this);
            if (RecorderService.recording) stopService(new Intent(this, RecorderService.class));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        CallManager.audio = state;
        CallManager.changed();
    }

    @Override
    public void onBringToForeground(boolean showDialpad) {
        super.onBringToForeground(showDialpad);
        Intent i = new Intent(this, InCallActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("keypad", showDialpad);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        CallManager.service = null;
        CallNotifier.cancelCall(this);
        return super.onUnbind(intent);
    }
}
