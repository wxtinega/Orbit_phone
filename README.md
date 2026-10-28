# Orbit Dial

A standalone Android phone app with a circular dial pad, your real call history and contacts, and a few things other dialers don't do.

## What it does
- **Keypad:** twelve keys around a ring like a clock, with a breathing green CALL button in the middle. Keys ripple and click as you tap, and a glowing satellite follows your finger. Long-press 0 for `+`, long-press ⌫ to clear.
- **Recents:** your phone's real call history with incoming, outgoing and missed icons, durations, contact photos and grouped repeat calls. Filter to missed calls only. Tap a call for actions (message, copy, add to contacts, edit before calling, assign gesture).
- **Contacts:** every contact with a phone number, alphabetical sections, photos, favourites strip and search by name or number. Tap for a contact card with call and message buttons; long-press to call straight away.

## The edge
- **Gesture Speed Dial:** drag across three or more keys without lifting your finger (say 1 → 5 → 9) and link it to a contact. Drawing that path calls them instantly. Assign gestures from the dial, any contact card, or any recent call.
- **Smart dial search:** start typing on the dial and matching contacts appear, by number or by name spelled on the keys (typing 6-8-6 finds "Mum").
- **Call insights:** calls today, talk time, missed calls and who you've talked to most this week, at the top of Recents.
- **Lively design:** slowly drifting colour glows that turn green when a number is ready to call, animated tab switches and a slide-up contact card.

Everything stays on the phone. Orbit Dial reads contacts and call history only to show them to you.

## Build the APK
Every push to this repository builds a new APK automatically: open the **Actions** tab, open the latest "Build APK" run and download **OrbitDial-apk**.

You can also open the folder in Android Studio and choose Build → Build APK(s).

## Install on your phone
Open `OrbitDial.apk`, allow "Install unknown apps" if asked, and install. On first launch, allow Contacts, Call logs and Phone so every tab works.

## Technical notes
- Pure Java, Android framework only: no third-party libraries.
- minSdk 26 (Android 8.0), targetSdk 34.
- The release build is signed with the debug key so it installs directly. Use your own keystore before publishing anywhere.
- Orbit Dial places outgoing calls; the in-call screen is still your phone's own.
