#!/bin/bash
# Seeds a test phone with contacts and calls, then screenshots every screen in light and dark mode.
mkdir -p shots
PKG=com.orbitdial.app
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }
start() { adb shell am start -W -n "$PKG/$1" "${@:2}" > /dev/null; }
top() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | tr -d '\r'; }
tapdesc() { # taps the view whose accessibility label is $1
  adb shell uiautomator dump /sdcard/u.xml > /dev/null 2>&1
  local b=$(adb shell cat /sdcard/u.xml | tr '>' '\n' | grep "content-desc=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
  set -- $b
  if [ -z "$4" ]; then echo "  (could not find button)" >> shots/back.txt; return; fi
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
check() { sleep 2; echo "$1 -> $(top)" >> shots/back.txt; }

adb root; sleep 3; adb wait-for-device
adb install -r -g OrbitDial.apk || exit 1
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

add_contact() { # name (spaces as _), number, starred
  local name="${1//_/\\ }"
  adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
  local id=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id\ DESC" | head -1 | sed 's/.*_id=\([0-9]*\).*/\1/' | tr -d '\r')
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:$name"
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:$2 --bind data2:i:2
  if [ "$3" = 1 ]; then adb shell content update --uri content://com.android.contacts/raw_contacts --bind starred:i:1 --where "_id=$id"; fi
}
add_contact Grace_Wanjiru 0712345678 1
add_contact Brian_Otieno 0722111222 1
add_contact Amina_Hassan 0733444555 1
add_contact Daniel_Kiprop 0700999888 0
add_contact Esther_Muthoni 0711222333 0
add_contact Felix_Njoroge 0799888777 0
add_contact Mum 0721000111 1
add_contact Office 0202345678 0

now=$(( $(date +%s) * 1000 ))
add_call() { adb shell content insert --uri content://call_log/calls --bind number:s:$1 --bind type:i:$2 --bind date:l:$(( now - $3 * 60000 )) --bind duration:i:$4 --bind new:i:1; }
add_call 0712345678 1 5 312
add_call 0733444555 3 40 0
add_call 0733444555 3 42 0
add_call 0722111222 2 90 75
add_call 0788123123 1 200 44
add_call 0721000111 2 1500 900
add_call 0700999888 3 1700 0
add_call 0711222333 1 3000 128

adb shell cmd role add-role-holder android.app.role.DIALER $PKG 0
adb shell cmd role get-role-holders android.app.role.DIALER > shots/role.txt
adb shell cmd uimode night no

start .MainActivity; shot 01-setup-light 4
start .MainActivity --ez skip_setup true; shot 02-keypad-light
adb shell am start -W -a android.intent.action.DIAL -d tel:0712 > /dev/null; shot 03-typing-light
start .MainActivity --ei tab 1; shot 04-recents-light
start .MainActivity --ei tab 2; shot 05-contacts-light
cid=$(adb shell content query --uri content://com.android.contacts/contacts --projection _id --where "display_name=\'Grace\ Wanjiru\'" | head -1 | sed 's/.*_id=\([0-9]*\).*/\1/' | tr -d '\r')
start .ContactActivity --el id $cid; shot 06-contact-light
tapdesc Back; check "Contact page, back arrow"
start .ContactActivity --el id $cid; sleep 2; adb shell input keyevent KEYCODE_BACK; check "Contact page, back key"
start .SettingsActivity; shot 07-settings-light
adb shell input swipe 540 1800 540 600 300; shot 08-settings-light-2 2
tapdesc Back; check "Settings, back arrow"
start .SettingsActivity; sleep 2; adb shell input keyevent KEYCODE_BACK; check "Settings, back key"
start .MainActivity --ei tab 2; sleep 2; adb shell input tap 540 1050; sleep 3; echo "Opened from Contacts list -> $(top)" >> shots/back.txt
tapdesc Back; check "Contact opened from list, back arrow"
adb shell input keyevent KEYCODE_BACK; check "Contacts tab, back key"

# Incoming and in-call screens
adb shell input keyevent KEYCODE_HOME
adb emu gsm call 0712345678; shot 09-incoming-light 4
adb emu gsm accept 0712345678; shot 10-incall-light 4
tapdesc Keypad; shot 10b-incall-keypad 2
adb shell input keyevent KEYCODE_BACK; shot 10c-incall-after-back 2
adb shell input keyevent KEYCODE_BACK; check "In call, back key"
adb shell am start -W -n "$PKG/.InCallActivity" > /dev/null; sleep 2
adb emu gsm cancel 0712345678; shot 11-ended-light 2

# Dark mode and the classic dial pad
adb shell cmd uimode night yes; sleep 2
adb shell am force-stop $PKG
start .MainActivity --ez skip_setup true; shot 12-keypad-dark
start .MainActivity --ei tab 1; shot 13-recents-dark
start .MainActivity --ei tab 2; shot 14-contacts-dark
start .ContactActivity --el id $cid; shot 15-contact-dark
uid=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
adb shell "sed -i 's#</map>#<string name=\"dial\">grid</string></map>#' /data/data/$PKG/shared_prefs/settings.xml; chown $uid:$uid /data/data/$PKG/shared_prefs/settings.xml"
adb shell am force-stop $PKG
start .MainActivity --ez skip_setup true; shot 16-grid-dark
adb shell am start -W -a android.intent.action.DIAL -d tel:0733 > /dev/null; shot 17-grid-typing-dark
adb shell input keyevent KEYCODE_HOME
adb emu gsm call 0788123123; shot 18-incoming-unknown-dark 4
adb emu gsm accept 0788123123; shot 19-incall-dark 4
adb emu gsm cancel 0788123123; shot 20-ended-dark 2

adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -E "AndroidRuntime|FATAL|orbitdial" | tail -200 > shots/log.txt
echo "done"
