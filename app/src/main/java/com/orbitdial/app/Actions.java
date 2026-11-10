package com.orbitdial.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;

import java.util.List;
import java.util.Locale;

/** Everything that leaves the app (calls, texts, WhatsApp, other apps), with friendly failures. */
final class Actions {
    private Actions() {}

    static final int REQ_CALL_PERMISSION = 7101;
    static final int REQ_DEFAULT_DIALER = 7102;

    private static String pendingNumber;

    static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------ phone role

    static boolean isDefaultPhoneApp(Context c) {
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            return tm != null && c.getPackageName().equals(tm.getDefaultDialerPackage());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void askToBeDefault(Activity a) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = a.getSystemService(RoleManager.class);
                if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    openDefaultAppsSettings(a);
                    return;
                }
                i = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
            } else {
                i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, a.getPackageName());
            }
            a.startActivityForResult(i, REQ_DEFAULT_DIALER);
        } catch (RuntimeException e) {
            openDefaultAppsSettings(a);
        }
    }

    static void openDefaultAppsSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (RuntimeException e) {
            openAppSettings(a);
        }
    }

    static void openAppSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.getPackageName(), null)));
        } catch (RuntimeException e) {
            Ui.snack(a, "Open Settings > Apps > Orbit Dial to change this.", null, null);
        }
    }

    // ------------------------------------------------------------------ calls

    static boolean isEmergency(Context c, String number) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                TelephonyManager t = c.getSystemService(TelephonyManager.class);
                return t != null && t.isEmergencyNumber(number);
            }
            //noinspection deprecation
            return PhoneNumberUtils.isEmergencyNumber(number);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Places a call, asking which SIM to use when the phone has two and no default is set. */
    static void call(Activity a, String number) {
        if (number == null || number.trim().isEmpty()) {
            Ui.snack(a, "Type a number first.", null, null);
            return;
        }
        final String n = number.trim();
        if (isEmergency(a, n)) {
            // Never put anything in the way of an emergency call.
            place(a, n, null);
            return;
        }
        if (!has(a, Manifest.permission.CALL_PHONE) && !isDefaultPhoneApp(a)) {
            pendingNumber = n;
            a.requestPermissions(new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE}, REQ_CALL_PERMISSION);
            return;
        }
        List<PhoneAccountHandle> sims = sims(a);
        if (sims.size() > 1) {
            String saved = Prefs.simFor(a, n);
            if (saved != null) {
                for (PhoneAccountHandle h : sims) {
                    if (saved.equals(h.getId())) {
                        place(a, n, h);
                        return;
                    }
                }
            }
            if (defaultSim(a) == null) {
                chooseSim(a, n, h -> place(a, n, h));
                return;
            }
        }
        place(a, n, null);
    }

    static void onPermissionResult(Activity a, int code, int[] results) {
        if (code != REQ_CALL_PERMISSION || pendingNumber == null) return;
        String n = pendingNumber;
        pendingNumber = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            call(a, n);
        } else {
            // Without the permission the phone's own dialer can still make the call.
            try {
                a.startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)));
            } catch (RuntimeException e) {
                Ui.snack(a, "Allow the Phone permission to make calls.", "Settings", () -> openAppSettings(a));
            }
        }
    }

    private static void place(Activity a, String number, PhoneAccountHandle sim) {
        Uri uri = Uri.fromParts("tel", number, null);
        try {
            TelecomManager tm = a.getSystemService(TelecomManager.class);
            Bundle extras = new Bundle();
            if (sim != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim);
            tm.placeCall(uri, extras);
        } catch (SecurityException | NullPointerException e) {
            try {
                a.startActivity(new Intent(Intent.ACTION_CALL, uri));
            } catch (RuntimeException e2) {
                try {
                    a.startActivity(new Intent(Intent.ACTION_DIAL, uri));
                } catch (RuntimeException e3) {
                    Ui.snack(a, "This device can't make phone calls.", null, null);
                }
            }
        }
    }

    static void voicemail(Activity a) {
        try {
            a.getSystemService(TelecomManager.class).placeCall(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null), new Bundle());
        } catch (RuntimeException e) {
            Ui.snack(a, "Voicemail isn't set up for this SIM.", null, null);
        }
    }

    static List<PhoneAccountHandle> sims(Context c) {
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            return tm.getCallCapablePhoneAccounts();
        } catch (RuntimeException e) {
            return new java.util.ArrayList<>();
        }
    }

    static PhoneAccountHandle defaultSim(Context c) {
        try {
            return c.getSystemService(TelecomManager.class).getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String simLabel(Context c, PhoneAccountHandle h) {
        try {
            PhoneAccount acc = c.getSystemService(TelecomManager.class).getPhoneAccount(h);
            if (acc != null && acc.getLabel() != null) return acc.getLabel().toString();
        } catch (RuntimeException ignored) {
        }
        return "SIM";
    }

    interface SimChoice {
        void chosen(PhoneAccountHandle h);
    }

    static void chooseSim(Activity a, String number, SimChoice cb) {
        List<PhoneAccountHandle> sims = sims(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        Switch remember = Ui.toggle(a, false, (b, v) -> { });
        Dialog[] d = new Dialog[1];
        for (int i = 0; i < sims.size(); i++) {
            PhoneAccountHandle h = sims.get(i);
            int tint = Ui.accent;
            try {
                PhoneAccount acc = a.getSystemService(TelecomManager.class).getPhoneAccount(h);
                if (acc != null && acc.getHighlightColor() != 0) tint = acc.getHighlightColor() | 0xFF000000;
            } catch (RuntimeException ignored) {
            }
            LinearLayout r = Ui.row(a, R.drawable.ic_sim, simLabel(a, h), "SIM " + (i + 1), null, v -> {
                if (number != null && remember.isChecked()) Prefs.setSimFor(a, number, h.getId());
                d[0].dismiss();
                cb.chosen(h);
            });
            ((ImageView) r.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(tint));
            list.addView(Ui.press(r));
        }
        if (number != null) {
            list.addView(Ui.hairline(a));
            list.addView(Ui.row(a, 0, "Remember for this number", null, remember, null));
        }
        d[0] = Ui.sheet(a, "Call with", list);
        d[0].show();
    }

    // -------------------------------------------------------- other apps

    static void sms(Activity a, String number) {
        if (TextUtils.isEmpty(number)) return;
        try {
            a.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)));
        } catch (ActivityNotFoundException e) {
            Ui.snack(a, "No messaging app found on this phone.", null, null);
        }
    }

    /** "+254 712 345 678" or "0712 345 678" both become "254712345678" for WhatsApp. */
    static String international(Context c, String number) {
        String iso = country(c);
        String e164 = null;
        try {
            e164 = PhoneNumberUtils.formatNumberToE164(number, iso);
        } catch (RuntimeException ignored) {
        }
        return Data.digits(e164 != null ? e164 : number);
    }

    static String country(Context c) {
        try {
            TelephonyManager t = c.getSystemService(TelephonyManager.class);
            String iso = t != null ? t.getNetworkCountryIso() : null;
            if (TextUtils.isEmpty(iso) && t != null) iso = t.getSimCountryIso();
            if (!TextUtils.isEmpty(iso)) return iso.toUpperCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
        }
        return Locale.getDefault().getCountry();
    }

    static void whatsAppChat(Activity a, String number) {
        String pkg = Phonebook.whatsAppPackage(a);
        if (pkg == null) {
            Ui.snack(a, "WhatsApp isn't installed on this phone.", null, null);
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + international(a, number)));
            i.setPackage(pkg);
            a.startActivity(i);
        } catch (RuntimeException e) {
            Ui.snack(a, "Couldn't open WhatsApp. Please try again.", null, null);
        }
    }

    static void whatsAppCall(Activity a, long contactId, String number) {
        long dataId = Phonebook.whatsAppCallDataId(a, contactId, number);
        if (dataId < 0) {
            Ui.snack(a, "This person isn't on WhatsApp, or WhatsApp hasn't synced your contacts yet.", "Open chat",
                    () -> whatsAppChat(a, number));
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId),
                    "vnd.android.cursor.item/vnd.com.whatsapp.voip.call");
            String pkg = Phonebook.whatsAppPackage(a);
            if (pkg != null) i.setPackage(pkg);
            a.startActivity(i);
        } catch (RuntimeException e) {
            whatsAppChat(a, number);
        }
    }

    static void copy(Activity a, String text) {
        if (TextUtils.isEmpty(text)) return;
        ClipboardManager cm = a.getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Phone number", text));
            if (Build.VERSION.SDK_INT < 33) Ui.snack(a, "Number copied.", null, null);
        }
    }

    static void addContact(Activity a, String number) {
        Intent i = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
        if (!TextUtils.isEmpty(number)) i.putExtra(ContactsContract.Intents.Insert.PHONE, number);
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Ui.snack(a, "No contacts app found to save this number.", null, null);
        }
    }

    static void editContact(Activity a, long id, String lookupKey) {
        try {
            Intent i = new Intent(Intent.ACTION_EDIT, ContactsContract.Contacts.getLookupUri(id, lookupKey));
            i.putExtra("finishActivityOnSaveCompleted", true);
            a.startActivity(i);
        } catch (RuntimeException e) {
            Ui.snack(a, "No contacts app found to edit this contact.", null, null);
        }
    }

    static void openContact(Activity a, long contactId, String number) {
        Intent i = new Intent(a, ContactActivity.class);
        i.putExtra("id", contactId);
        i.putExtra("number", number);
        a.startActivity(i);
    }

    // -------------------------------------------------------- home shortcuts

    /** Long-press the app icon to call favourites straight away. */
    static void updateShortcuts(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            android.content.pm.ShortcutManager sm = c.getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            List<android.content.pm.ShortcutInfo> list = new java.util.ArrayList<>();
            int max = Math.min(4, sm.getMaxShortcutCountPerActivity());
            for (Data.Contact ct : Data.contacts) {
                if (!ct.starred || ct.numbers.isEmpty()) continue;
                Intent i = new Intent(MainActivity.ACTION_CALL_SHORTCUT).setClassName(c, MainActivity.ALIAS)
                        .putExtra("number", ct.primary()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                android.graphics.Bitmap photo = Data.loadBitmap(c, ct.thumbUri);
                Icon icon = photo != null ? Icon.createWithBitmap(circle(photo))
                        : Icon.createWithResource(c, R.drawable.ic_shortcut_person);
                list.add(new android.content.pm.ShortcutInfo.Builder(c, "fav_" + ct.id)
                        .setShortLabel(ct.name.split("\\s+")[0])
                        .setLongLabel("Call " + ct.name)
                        .setIcon(icon)
                        .setIntent(i)
                        .build());
                if (list.size() >= max) break;
            }
            sm.setDynamicShortcuts(list);
        } catch (RuntimeException ignored) {
        }
    }

    private static android.graphics.Bitmap circle(android.graphics.Bitmap src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        cv.translate(-(src.getWidth() - s) / 2f, -(src.getHeight() - s) / 2f);
        cv.drawCircle(src.getWidth() / 2f, src.getHeight() / 2f, s / 2f, p);
        return out;
    }
}
