package com.orbitdial.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;

/** Incoming-call, call-in-progress and missed-call notifications. */
final class CallNotifier {
    private CallNotifier() {}

    static final String CH_INCOMING = "incoming_v2", CH_ONGOING = "ongoing", CH_MISSED = "missed", CH_RECORDING = "recording";
    static final int ID_CALL = 1, ID_MISSED = 2, ID_RECORDING = 3;
    private static final int FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel in = new NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        in.setSound(null, null); // the phone's ringtone already plays
        in.enableVibration(false);
        in.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(in);
        NotificationChannel on = new NotificationChannel(CH_ONGOING, "Calls in progress", NotificationManager.IMPORTANCE_LOW);
        on.setShowBadge(false);
        nm.createNotificationChannel(on);
        nm.createNotificationChannel(new NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel rec = new NotificationChannel(CH_RECORDING, "Call recording", NotificationManager.IMPORTANCE_LOW);
        rec.setShowBadge(false);
        nm.createNotificationChannel(rec);
    }

    private static PendingIntent screen(Context c, boolean answer, int code) {
        Intent i = new Intent(c, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (answer) i.putExtra("answer", true);
        return PendingIntent.getActivity(c, code, i, FLAGS);
    }

    private static PendingIntent broadcast(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, CallActionReceiver.class).setAction(action), FLAGS);
    }

    private static String title(CallManager.Info i) {
        if (i.hidden) return "Private number";
        return i.name != null ? i.name : i.number;
    }

    static void showIncoming(Context c, Call call) {
        channels(c);
        CallManager.Info info = CallManager.info(call);
        PendingIntent full = screen(c, false, 10);
        PendingIntent answer = screen(c, true, 11);
        PendingIntent decline = broadcast(c, CallActionReceiver.DECLINE, 12);
        Notification.Builder b = new Notification.Builder(c, CH_INCOMING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(title(info))
                .setContentText(info.name != null && !info.hidden ? "Incoming call  ·  " + info.number : "Incoming call")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Ui.accent)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (info.photo != null) b.setLargeIcon(info.photo);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                Person.Builder p = new Person.Builder().setName(title(info)).setImportant(true);
                if (info.photo != null) p.setIcon(Icon.createWithBitmap(info.photo));
                b.setStyle(Notification.CallStyle.forIncomingCall(p.build(), decline, answer));
            } else {
                b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_call_end), "Decline", decline).build());
                b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_call), "Answer", answer).build());
            }
            notify(c, ID_CALL, b.build());
        } catch (RuntimeException e) {
            b.setStyle(null);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_call_end), "Decline", decline).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_call), "Answer", answer).build());
            notify(c, ID_CALL, b.build());
        }
    }

    /** Keeps the notification in step with the calls: incoming, in progress, or gone. */
    static void refresh(Context c) {
        Call ringing = CallManager.ringing();
        if (ringing != null) {
            showIncoming(c, ringing);
            return;
        }
        Call p = CallManager.primary();
        if (p == null) {
            cancelCall(c);
            return;
        }
        channels(c);
        CallManager.Info info = CallManager.info(p);
        int s = CallManager.state(p);
        String status = s == Call.STATE_HOLDING ? "On hold" : s == Call.STATE_ACTIVE ? "Call in progress" : "Calling";
        if (CallManager.isConference(p)) status = "Conference call";
        Notification.Builder b = new Notification.Builder(c, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(CallManager.isConference(p) ? "Conference call" : title(info))
                .setContentText(status)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Ui.accent)
                .setContentIntent(screen(c, false, 13))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_call_end), "Hang up",
                        broadcast(c, CallActionReceiver.HANG_UP, 14)).build());
        long connect = p.getDetails().getConnectTimeMillis();
        if (s == Call.STATE_ACTIVE && connect > 0) {
            b.setWhen(connect).setUsesChronometer(true).setShowWhen(true);
        }
        if (info.photo != null) b.setLargeIcon(info.photo);
        notify(c, ID_CALL, b.build());
    }

    static void cancelCall(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_CALL);
    }

    static void missed(Context c, String number, int count) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (count <= 0) {
            nm.cancel(ID_MISSED);
            return;
        }
        channels(c);
        String name = null;
        if (number != null && !number.isEmpty()) {
            String[] r = Data.quickLookup(c, number);
            if (r != null) name = r[0];
        }
        String who = name != null ? name : number != null && !number.isEmpty() ? number : "a private number";
        Intent recents = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_TAB, MainActivity.RECENTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder b = new Notification.Builder(c, CH_MISSED)
                .setSmallIcon(R.drawable.ic_call_missed)
                .setContentTitle(count == 1 ? "Missed call" : count + " missed calls")
                .setContentText(count == 1 ? "From " + who : "Latest from " + who)
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setColor(Ui.DANGER)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 20, recents, FLAGS));
        if (number != null && !number.isEmpty()) {
            Intent callBack = new Intent(MainActivity.ACTION_CALL_SHORTCUT).setClassName(c, MainActivity.ALIAS)
                    .putExtra("number", number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_call), "Call back",
                    PendingIntent.getActivity(c, 21, callBack, FLAGS)).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_message), "Message",
                    PendingIntent.getActivity(c, 22, sms, FLAGS)).build());
        }
        notify(c, ID_MISSED, b.build());
    }

    static void notify(Context c, int id, Notification n) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(id, n);
        } catch (RuntimeException ignored) {
            // Notifications turned off: the call screen still opens on its own.
        }
    }
}
