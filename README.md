# Orbit Dial

A standalone Android phone dialer with a circular dial pad and **Gesture Speed Dial**.

## The dial pad
The twelve keys sit around a ring like the hours on a clock (1 at one o'clock … # at twelve), with a green CALL button in the centre. A small "satellite" glides around the outer orbit to follow your finger, and every key gives a DTMF tone and a haptic tick.

- Tap a key: type the digit
- Long-press 0: type `+`
- Tap ⌫ to delete, long-press it to clear
- Tap CALL with nothing typed: brings back your last number

## New feature: Gesture Speed Dial
Drag your finger across three or more keys without lifting it (for example 1 → 5 → 9). A glowing trail follows your path and numbers each step. When you let go:

- If that path is new, Orbit Dial asks who it should call and saves it.
- If it is already saved, it calls that person immediately.

So instead of scrolling contacts, you "draw" a person: a triangle for Mum, a straight line for work. Manage your paths under **Gestures**. Calls you place appear under **Recents**. Everything is stored only on the phone.

## Build the APK

**Option A: GitHub (no software to install)**
1. Create a new GitHub repository and upload this whole folder.
2. Open the **Actions** tab; the "Build APK" workflow runs automatically (or press *Run workflow*).
3. When it turns green (about 3–5 minutes), open the run and download **OrbitDial-apk**. Inside is `OrbitDial.apk`.

**Option B: Android Studio**
1. File → Open → choose this folder. Let Gradle sync.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. The APK is in `app/build/outputs/apk/`.

## Install on your phone
Copy `OrbitDial.apk` to the phone, open it, and allow "Install unknown apps" for your file manager when asked. The first time you call, allow the Phone permission (if you refuse, Orbit Dial passes the number to your normal dialer instead).

## Technical notes
- Pure Java, Android framework only: no third-party libraries.
- minSdk 26 (Android 8.0), targetSdk 34.
- The release build is signed with the debug key so it installs directly. Use your own keystore before publishing to the Play Store.
- Orbit Dial places outgoing calls. The in-call screen is still your phone's own (replacing that requires becoming the system's default phone app, which is a much larger project).
