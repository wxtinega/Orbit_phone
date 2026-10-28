package com.orbitdial.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.util.LruCache;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads the phone's real contacts and call history. Nothing is uploaded anywhere. */
final class Data {
    private Data() {}

    static final ExecutorService IO = Executors.newFixedThreadPool(2);
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Phone {
        final String number, label;

        Phone(String number, String label) {
            this.number = number;
            this.label = label;
        }
    }

    static final class Contact {
        final long id;
        final String lookupKey;
        final String name;
        boolean starred;
        String photoUri;
        final List<Phone> phones = new ArrayList<>();
        String t9Full = "";
        final List<String> t9Words = new ArrayList<>();
        final List<String> digitsList = new ArrayList<>();

        Contact(long id, String lookupKey, String name) {
            this.id = id;
            this.lookupKey = lookupKey;
            this.name = name == null || name.isEmpty() ? "Unknown" : name;
        }

        String primaryNumber() {
            return phones.isEmpty() ? "" : phones.get(0).number;
        }

        char section() {
            char ch = name.isEmpty() ? '#' : Character.toUpperCase(name.charAt(0));
            return Character.isLetter(ch) ? ch : '#';
        }
    }

    static final class Call {
        final String number, name, numberLabel;
        final int type;
        final long date, duration;

        Call(String number, String name, String numberLabel, int type, long date, long duration) {
            this.number = number == null ? "" : number;
            this.name = name;
            this.numberLabel = numberLabel;
            this.type = type;
            this.date = date;
            this.duration = duration;
        }

        boolean missed() {
            return type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE;
        }

        String title() {
            if (name != null && !name.isEmpty()) return name;
            return number.isEmpty() ? "Private number" : number;
        }
    }

    // ------------------------------------------------------------ contacts

    static List<Contact> loadContacts(Context ctx) {
        Map<Long, Contact> byId = new LinkedHashMap<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] proj = {
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        };
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC")) {
            if (c == null) return new ArrayList<>();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                Contact ct = byId.get(id);
                if (ct == null) {
                    ct = new Contact(id, c.getString(1), c.getString(2));
                    ct.starred = c.getInt(6) != 0;
                    ct.photoUri = c.getString(7);
                    byId.put(id, ct);
                }
                String number = c.getString(3);
                if (number == null) continue;
                String digits = digits(number);
                if (ct.digitsList.contains(digits)) continue; // same number saved twice
                CharSequence label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        ctx.getResources(), c.getInt(4), c.getString(5));
                ct.phones.add(new Phone(number, label == null ? "" : label.toString()));
                ct.digitsList.add(digits);
            }
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
        List<Contact> out = new ArrayList<>(byId.values());
        for (Contact ct : out) buildT9(ct);
        return out;
    }

    static List<Call> loadCalls(Context ctx, int max) {
        List<Call> out = new ArrayList<>();
        String[] proj = {
                CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_NUMBER_LABEL,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
        };
        try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, proj, null, null,
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < max) {
                out.add(new Call(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getLong(5)));
            }
        } catch (SecurityException e) {
            // permission not granted
        }
        return out;
    }

    // ---------------------------------------------------------------- T9

    private static final String T9_KEYS = "22233344455566677778889999";

    static String digits(String number) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char ch = number.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    private static String t9(String word) {
        StringBuilder sb = new StringBuilder();
        String w = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch >= 'a' && ch <= 'z') sb.append(T9_KEYS.charAt(ch - 'a'));
            else if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    private static void buildT9(Contact ct) {
        StringBuilder full = new StringBuilder();
        for (String w : ct.name.split("\\s+")) {
            String t = t9(w);
            if (!t.isEmpty()) {
                ct.t9Words.add(t);
                full.append(t);
            }
        }
        ct.t9Full = full.toString();
    }

    static final class Match {
        final Contact contact;
        final Phone phone;
        final boolean byName;

        Match(Contact contact, Phone phone, boolean byName) {
            this.contact = contact;
            this.phone = phone;
            this.byName = byName;
        }
    }

    /** Smart dial search: typed digits match phone numbers or names spelled on the keypad (T9). */
    static List<Match> search(List<Contact> contacts, String typed, int limit) {
        String q = digits(typed);
        List<Match> exact = new ArrayList<>(), name = new ArrayList<>(), contains = new ArrayList<>();
        if (q.length() < 2 || contacts == null) return exact;
        for (Contact ct : contacts) {
            if (ct.phones.isEmpty()) continue;
            boolean found = false;
            for (int i = 0; i < ct.digitsList.size(); i++) {
                String d = ct.digitsList.get(i);
                if (d.startsWith(q) || sameNumber(d, q)) {
                    exact.add(new Match(ct, ct.phones.get(i), false));
                    found = true;
                    break;
                } else if (d.contains(q)) {
                    contains.add(new Match(ct, ct.phones.get(i), false));
                    found = true;
                    break;
                }
            }
            if (found) continue;
            boolean nameHit = ct.t9Full.startsWith(q);
            if (!nameHit) for (String w : ct.t9Words) if (w.startsWith(q)) { nameHit = true; break; }
            if (nameHit) name.add(new Match(ct, ct.phones.get(0), true));
        }
        List<Match> all = new ArrayList<>(exact);
        all.addAll(name);
        all.addAll(contains);
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    /** Compares the last 9 digits so "+254712..." and "0712..." count as the same number. */
    static boolean sameNumber(String a, String b) {
        String da = digits(a), db = digits(b);
        if (da.length() < 7 || db.length() < 7) return da.equals(db) && !da.isEmpty();
        int n = Math.min(9, Math.min(da.length(), db.length()));
        return da.substring(da.length() - n).equals(db.substring(db.length() - n));
    }

    static Contact findByNumber(List<Contact> contacts, String number) {
        if (contacts == null || number == null || number.isEmpty()) return null;
        for (Contact ct : contacts) for (String d : ct.digitsList) if (sameNumber(d, number)) return ct;
        return null;
    }

    // -------------------------------------------------------------- photos

    private static final LruCache<String, Bitmap> PHOTOS = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Map<String, Boolean> MISSING = Collections.synchronizedMap(new HashMap<>());

    /** Loads a contact photo off the main thread and shows it if the avatar still shows that contact. */
    static void bindPhoto(Context ctx, AvatarView view, String uri) {
        view.setBoundKey(uri);
        if (uri == null || MISSING.containsKey(uri)) return;
        Bitmap cached = PHOTOS.get(uri);
        if (cached != null) {
            view.setPhoto(cached);
            return;
        }
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            Bitmap b = null;
            try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(uri))) {
                if (in != null) b = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
            final Bitmap result = b;
            if (result == null) MISSING.put(uri, true);
            else PHOTOS.put(uri, result);
            MAIN.post(() -> {
                if (result != null && uri.equals(view.getBoundKey())) view.setPhoto(result);
            });
        });
    }
}
