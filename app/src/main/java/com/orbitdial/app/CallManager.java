package com.orbitdial.app;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Live state of every call, shared by the call service, the call screen and notifications. */
final class CallManager {
    private CallManager() {}

    interface Listener {
        void onCallsChanged();
    }

    static CallService service;
    static final List<Call> calls = new ArrayList<>();
    static CallAudioState audio;
    private static final List<Listener> listeners = new ArrayList<>();
    private static final Map<Call, Info> info = new HashMap<>();

    /** Who is on a call, as best we know right now. */
    static final class Info {
        String number = "", name, photoUri, label;
        long contactId = -1;
        Bitmap photo;
        boolean hidden;
        long created;
    }

    /** What the screen shows after the last call ends. */
    static final class Ended {
        String number, name;
        long callTime, connectTime, seconds;
        int cause;
        String reason;
        long contactId = -1;
    }

    static Ended lastEnded;

    private static final Call.Callback CALLBACK = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            if (state == Call.STATE_DISCONNECTED) rememberEnded(call);
            changed();
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            changed();
        }

        @Override
        public void onChildrenChanged(Call call, List<Call> children) {
            changed();
        }

        @Override
        public void onConferenceableCallsChanged(Call call, List<Call> calls) {
            changed();
        }
    };

    static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onCallsChanged();
        if (service != null) CallNotifier.refresh(service);
    }

    static void add(Context ctx, Call c) {
        calls.add(c);
        c.registerCallback(CALLBACK);
        Info i = new Info();
        i.created = System.currentTimeMillis();
        Call.Details d = c.getDetails();
        Uri h = d.getHandle();
        i.number = h != null ? h.getSchemeSpecificPart() : "";
        if (i.number == null) i.number = "";
        i.hidden = d.getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED || i.number.isEmpty();
        if (Build.VERSION.SDK_INT >= 30 && d.getContactDisplayName() != null) i.name = d.getContactDisplayName();
        Data.Contact known = Data.find(i.number);
        if (known != null) {
            i.name = known.name;
            i.photoUri = known.photoUri != null ? known.photoUri : known.thumbUri;
            i.contactId = known.id;
            for (Data.Number n : known.numbers) if (Data.sameNumber(n.number, i.number)) i.label = n.label;
        }
        if (i.name == null && d.getCallerDisplayName() != null && !d.getCallerDisplayName().isEmpty()) {
            i.name = d.getCallerDisplayName();
        }
        info.put(c, i);
        changed();
        // Fill in the name and photo straight from the phone's contacts.
        Context app = ctx.getApplicationContext();
        Data.IO.execute(() -> {
            if (i.contactId < 0 && !i.hidden) {
                String[] r = Data.quickLookup(app, i.number);
                if (r != null) {
                    i.name = r[0];
                    i.photoUri = r[1];
                    try {
                        i.contactId = Long.parseLong(r[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (i.photoUri != null) i.photo = Data.loadBitmap(app, i.photoUri);
            Data.MAIN.post(CallManager::changed);
        });
    }

    static void remove(Call c) {
        c.unregisterCallback(CALLBACK);
        if (lastEnded == null || calls.size() <= 1) rememberEnded(c);
        calls.remove(c);
        info.remove(c);
        changed();
    }

    private static void rememberEnded(Call c) {
        Info i = info.get(c);
        if (i == null || isConference(c)) return;
        Ended e = new Ended();
        e.number = i.number;
        e.name = i.name;
        e.contactId = i.contactId;
        Call.Details d = c.getDetails();
        e.callTime = d.getCreationTimeMillis() > 0 ? d.getCreationTimeMillis() : i.created;
        e.connectTime = d.getConnectTimeMillis();
        e.seconds = e.connectTime > 0 ? Math.max(0, (System.currentTimeMillis() - e.connectTime) / 1000) : 0;
        DisconnectCause dc = d.getDisconnectCause();
        e.cause = dc != null ? dc.getCode() : DisconnectCause.UNKNOWN;
        e.reason = dc != null && dc.getLabel() != null ? dc.getLabel().toString() : null;
        lastEnded = e;
    }

    static Info info(Call c) {
        Info i = c == null ? null : info.get(c);
        return i != null ? i : new Info();
    }

    // ------------------------------------------------------------------ state

    @SuppressWarnings("deprecation")
    static int state(Call c) {
        if (c == null) return Call.STATE_DISCONNECTED;
        return Build.VERSION.SDK_INT >= 31 ? c.getDetails().getState() : c.getState();
    }

    static boolean isConference(Call c) {
        return c != null && c.getDetails().hasProperty(Call.Details.PROPERTY_CONFERENCE);
    }

    /** Calls shown on screen: conference members are folded into their conference. */
    static List<Call> topLevel() {
        List<Call> out = new ArrayList<>();
        for (Call c : calls) if (c.getParent() == null && state(c) != Call.STATE_DISCONNECTED) out.add(c);
        return out;
    }

    static Call find(int... states) {
        for (int s : states) for (Call c : topLevel()) if (state(c) == s) return c;
        return null;
    }

    static Call ringing() {
        return find(Call.STATE_RINGING, Build.VERSION.SDK_INT >= 29 ? Call.STATE_SIMULATED_RINGING : Call.STATE_RINGING);
    }

    /** The call the screen is about: ringing, then live, then being placed, then on hold. */
    static Call primary() {
        Call c = ringing();
        if (c == null) c = find(Call.STATE_ACTIVE);
        if (c == null) c = find(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_NEW);
        if (c == null) c = find(Call.STATE_HOLDING);
        if (c == null) c = find(Call.STATE_DISCONNECTING);
        return c;
    }

    static Call other(Call primary) {
        for (Call c : topLevel()) if (c != primary) return c;
        return null;
    }

    static boolean hasCalls() {
        return !topLevel().isEmpty();
    }

    // ---------------------------------------------------------------- actions

    static void answer(Call c) {
        if (c != null) c.answer(VideoProfile.STATE_AUDIO_ONLY);
    }

    static void decline(Call c) {
        if (c != null) c.reject(false, null);
    }

    static void declineWithMessage(Call c, String text) {
        if (c != null) c.reject(true, text);
    }

    static void hangUp(Call c) {
        if (c != null) c.disconnect();
    }

    static void toggleHold(Call c) {
        if (c == null) return;
        if (state(c) == Call.STATE_HOLDING) c.unhold();
        else if (c.getDetails().can(Call.Details.CAPABILITY_HOLD)) c.hold();
    }

    static boolean canHold(Call c) {
        return c != null && (c.getDetails().can(Call.Details.CAPABILITY_HOLD) || state(c) == Call.STATE_HOLDING);
    }

    static boolean muted() {
        return audio != null && audio.isMuted();
    }

    static void setMuted(boolean m) {
        if (service != null) service.setMuted(m);
    }

    static int route() {
        return audio != null ? audio.getRoute() : CallAudioState.ROUTE_EARPIECE;
    }

    static int routes() {
        return audio != null ? audio.getSupportedRouteMask() : CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER;
    }

    static void setRoute(int r) {
        if (service != null) service.setAudioRoute(r);
    }

    static List<BluetoothDevice> bluetooth() {
        List<BluetoothDevice> out = new ArrayList<>();
        if (audio != null && Build.VERSION.SDK_INT >= 28) out.addAll(audio.getSupportedBluetoothDevices());
        return out;
    }

    static BluetoothDevice activeBluetooth() {
        return audio != null && Build.VERSION.SDK_INT >= 28 ? audio.getActiveBluetoothDevice() : null;
    }

    static void useBluetooth(BluetoothDevice d) {
        if (service != null && Build.VERSION.SDK_INT >= 28 && d != null) service.requestBluetoothAudio(d);
        else setRoute(CallAudioState.ROUTE_BLUETOOTH);
    }

    static boolean canMerge(Call c) {
        if (c == null) return false;
        return !c.getConferenceableCalls().isEmpty() || c.getDetails().can(Call.Details.CAPABILITY_MERGE_CONFERENCE);
    }

    static void merge(Call c) {
        if (c == null) return;
        List<Call> list = c.getConferenceableCalls();
        if (!list.isEmpty()) c.conference(list.get(0));
        else if (c.getDetails().can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) c.mergeConference();
    }

    static void swap(Call active, Call held) {
        if (active != null && active.getDetails().can(Call.Details.CAPABILITY_SWAP_CONFERENCE)) {
            active.swapConference();
            return;
        }
        if (held != null && state(held) == Call.STATE_HOLDING) held.unhold(); // Telecom holds the other one
        else if (active != null) active.hold();
    }

    static void dtmf(Call c, char digit) {
        if (c == null) return;
        c.playDtmfTone(digit);
        Data.MAIN.postDelayed(c::stopDtmfTone, 160);
    }
}
