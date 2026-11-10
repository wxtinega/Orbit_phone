package com.orbitdial.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Records a call while it lasts. Files stay in T.I Dial's own folder on this phone. */
public class RecorderService extends Service {
    static volatile boolean recording;
    static volatile long startedAt;
    static volatile String lastError;

    private MediaRecorder recorder;
    private File file;
    private String number;
    private long callTime;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (recording || intent == null) return START_NOT_STICKY;
        number = intent.getStringExtra("number");
        callTime = intent.getLongExtra("callTime", System.currentTimeMillis());
        String name = intent.getStringExtra("name");
        CallNotifier.channels(this);
        Intent open = new Intent(this, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(this, CallNotifier.CH_RECORDING)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("Recording call")
                .setContentText(name != null ? name : number)
                .setColor(Ui.DANGER)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 30, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(CallNotifier.ID_RECORDING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(CallNotifier.ID_RECORDING, n);
        } catch (RuntimeException e) {
            lastError = "Recording couldn't start. Open the call screen and try again.";
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean callAudio = Prefs.REC_CALL_AUDIO.equals(Prefs.recording(this));
        if (!start(callAudio ? MediaRecorder.AudioSource.VOICE_CALL : MediaRecorder.AudioSource.MIC)
                && !(callAudio && start(MediaRecorder.AudioSource.MIC))) {
            lastError = "This phone won't let apps record calls.";
            stopSelf();
            return START_NOT_STICKY;
        }
        lastError = null;
        recording = true;
        startedAt = System.currentTimeMillis();
        CallManager.changed();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private boolean start(int source) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        file = new File(dir, "Call_" + Data.digits(number) + "_" + stamp + ".m4a");
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(source);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            return true;
        } catch (Exception e) {
            if (recorder != null) {
                try {
                    recorder.release();
                } catch (RuntimeException ignored) {
                }
            }
            recorder = null;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (recorder != null) {
            long secs = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
            boolean ok = true;
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                ok = false; // too short to save
            }
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
            }
            recorder = null;
            if (ok && file != null && file.length() > 0) {
                Notes.add(this, number, Notes.RECORDING, callTime, file.getAbsolutePath(), secs);
            } else if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        recording = false;
        startedAt = 0;
        stopForeground(STOP_FOREGROUND_REMOVE);
        CallManager.changed();
    }
}
