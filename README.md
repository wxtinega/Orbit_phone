# Orbit Dial

A full phone app for Android with a clean glass design, your real contacts and call history, and a few things other dialers don't do.

## Highlights
- **Your phone app:** set Orbit Dial as the default phone app and it shows incoming calls the moment they ring, even on the lock screen, with the caller's photo.
- **Two dial pads:** Orbit (keys around a ring) or Classic (the 3 x 4 grid). Switch any time in Settings.
- **Gesture Speed Dial:** drag across three or more keys to call someone instantly.
- **In-call controls:** mute, keypad, speaker, Bluetooth and headset picker, hold, add call, merge, swap, conference management.
- **Notes after calls,** and optional call recording (Settings > Call recording).
- **Contacts:** photos you can set from your gallery, per-contact ringtones, favourites, nickname and keypad search, WhatsApp and Messages buttons, blocking.
- **Recents:** missed, incoming and outgoing filters, call insights, notes shown beside the call.
- **Looks:** orange accent (or five others), light, dark or automatic, glass that lets your wallpaper show through.

Everything stays on the phone.

## Getting the APK
Every push to this repository builds a new APK: open **Actions**, open the latest "Build APK" run, and download **OrbitDial-apk**.

Builds are signed with one fixed key (`app/orbit-release.jks`), so each new version installs over the last one. Because this repository is public, anyone could sign an app with that key. Keep the repository private, or replace the key, before sharing the app beyond your own phone.

## Automatic UI check
After each build, a second job installs the app on an Android emulator, adds sample contacts and calls, simulates incoming calls, and saves screenshots to the `ui-shots` branch.

## Technical notes
- Pure Java, Android framework only: no third-party libraries.
- minSdk 26 (Android 8.0), targetSdk 34.
- The phone system plays the ringtone, so per-contact ringtones set in Orbit Dial work everywhere.
- Call recording: Android limits recording calls on most phones. "Speaker" mode records through the microphone with the call on speaker. "Call audio" tries the phone's call stream and falls back to the microphone.
