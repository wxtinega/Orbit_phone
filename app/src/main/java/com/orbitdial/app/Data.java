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
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Phone;
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

/** Reads contacts and call history on the phone. Everything stays on the device. */
final class Data {
    private Data() {}

    static final ExecutorService IO = Executors.newFixedThreadPool(3);
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    // In-memory snapshot shared by every screen, so search and lookups are instant.
    static volatile List<Contact> contacts = new ArrayList<>();
    static volatile Map<String, Contact> byKey = new HashMap<>();
    static volatile List<Call> calls = new ArrayList<>();
    static volatile boolean loaded;

    static final class Number {
        final String number, label;

        Number(String number, String label) {
            this.number = number;
            this.label = label;
        }
    }

    static final class Contact {
        final long id;
        final String lookupKey, name;
        boolean starred;
        String thumbUri, photoUri, ringtone, nickname = "";
        final List<Number> numbers = new ArrayList<>();
        final List<String> digits = new ArrayList<>();
        String lowerName = "", t9Full = "";
        final List<String> t9Words = new ArrayList<>();

        Contact(long id, String lookupKey, String name) {
            this.id = id;
            this.lookupKey = lookupKey;
            this.name = name == null || name.trim().isEmpty() ? "No name" : name.trim();
        }

        String primary() {
            return numbers.isEmpty() ? "" : numbers.get(0).number;
        }

        char section() {
            if (name.isEmpty()) return '#';
            char ch = Character.toUpperCase(name.charAt(0));
            return Character.isLetter(ch) ? ch : '#';
        }
    }

    static final class Call {
        final long id;
        final String number, cachedName, numberLabel, accountId;
        final int type;
        final long date, duration;

        Call(long id, String number, String cachedName, String numberLabel, int type, long date, long duration, String accountId) {
            this.id = id;
            this.number = number == null ? "" : number;
            this.cachedName = cachedName;
            this.numberLabel = numberLabel;
            this.type = type;
            this.date = date;
            this.duration = duration;
            this.accountId = accountId;
        }

        boolean missed() {
            return type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
                    || type == CallLog.Calls.BLOCKED_TYPE;
        }

        boolean outgoing() {
            return type == CallLog.Calls.OUTGOING_TYPE;
        }
    }

    // --------------------------------------------------------------- loading

    static void loadAll(Context ctx) {
        List<Contact> c = loadContacts(ctx);
        Map<String, Contact> map = new HashMap<>();
        for (Contact ct : c) for (String d : ct.digits) if (!d.isEmpty()) map.put(key(d), ct);
        contacts = c;
        byKey = map;
        calls = loadCalls(ctx, 600);
        loaded = true;
    }

    private static List<Contact> loadContacts(Context ctx) {
        Map<Long, Contact> byId = new LinkedHashMap<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] proj = {Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER,
                Phone.TYPE, Phone.LABEL, Phone.STARRED, Phone.PHOTO_THUMBNAIL_URI, Phone.PHOTO_URI, Phone.CUSTOM_RINGTONE};
        try (Cursor c = cr.query(Phone.CONTENT_URI, proj, null, null, Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    Contact ct = byId.get(id);
                    if (ct == null) {
                        ct = new Contact(id, c.getString(1), c.getString(2));
                        ct.starred = c.getInt(6) != 0;
                        ct.thumbUri = c.getString(7);
                        ct.photoUri = c.getString(8);
                        ct.ringtone = c.getString(9);
                        byId.put(id, ct);
                    }
                    String number = c.getString(3);
                    if (number == null) continue;
                    String d = digits(number);
                    boolean dup = false;
                    for (String existing : ct.digits) if (sameNumber(existing, d)) { dup = true; break; }
                    if (dup) continue;
                    CharSequence label = Phone.getTypeLabel(ctx.getResources(), c.getInt(4), c.getString(5));
                    ct.numbers.add(new Number(number, label == null ? "" : label.toString()));
                    ct.digits.add(d);
                }
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        // Nicknames make search friendlier ("Mum" finds "Grace Wanjiru").
        try (Cursor c = cr.query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.Data.CONTACT_ID, Nickname.NAME},
                ContactsContract.Data.MIMETYPE + "=?", new String[]{Nickname.CONTENT_ITEM_TYPE}, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    Contact ct = byId.get(c.getLong(0));
                    if (ct != null && c.getString(1) != null) ct.nickname = c.getString(1);
                }
            }
        } catch (RuntimeException ignored) {
        }
        List<Contact> out = new ArrayList<>(byId.values());
        for (Contact ct : out) index(ct);
        return out;
    }

    private static List<Call> loadCalls(Context ctx, int max) {
        List<Call> out = new ArrayList<>();
        String[] proj = {CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_NUMBER_LABEL,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID};
        try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < max) {
                out.add(new Call(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4),
                        c.getLong(5), c.getLong(6), c.getString(7)));
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    // ----------------------------------------------------------------- lookup

    static String digits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    /** Last 9 digits: treats "+254 712 345 678" and "0712 345 678" as the same number. */
    static String key(String number) {
        String d = digits(number);
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    static boolean sameNumber(String a, String b) {
        String da = digits(a), db = digits(b);
        if (da.isEmpty() || db.isEmpty()) return false;
        if (da.length() < 7 || db.length() < 7) return da.equals(db);
        return key(da).equals(key(db));
    }

    static Contact find(String number) {
        if (number == null) return null;
        String d = digits(number);
        if (d.length() < 3) return null;
        Contact c = byKey.get(key(d));
        if (c != null) return c;
        if (d.length() < 7) {
            for (Contact ct : contacts) for (String x : ct.digits) if (x.equals(d)) return ct;
        }
        return null;
    }

    static Contact byId(long id) {
        for (Contact c : contacts) if (c.id == id) return c;
        return null;
    }

    static String nameFor(String number) {
        Contact c = find(number);
        return c != null ? c.name : null;
    }

    /** Direct, fast lookup used for incoming calls before the full list is loaded. */
    static String[] quickLookup(Context ctx, String number) {
        if (number == null || number.isEmpty()) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI, ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return new String[]{c.getString(0), c.getString(1), String.valueOf(c.getLong(2)), c.getString(3)};
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    // ----------------------------------------------------------------- search

    private static final String T9 = "22233344455566677778889999";

    private static String t9(String word) {
        StringBuilder sb = new StringBuilder();
        String w = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch >= 'a' && ch <= 'z') sb.append(T9.charAt(ch - 'a'));
            else if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    private static void index(Contact ct) {
        ct.lowerName = (ct.name + " " + ct.nickname).toLowerCase(Locale.getDefault());
        StringBuilder full = new StringBuilder();
        for (String w : (ct.name + " " + ct.nickname).split("\\s+")) {
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
        final Number number;
        final int score;

        Match(Contact contact, Number number, int score) {
            this.contact = contact;
            this.number = number;
            this.score = score;
        }
    }

    /**
     * One search for everything: name, nickname, number, or a name spelled on the keypad.
     * Best matches first (starts-with beats contains, favourites break ties).
     */
    static List<Match> search(String query, boolean keypad, int limit) {
        List<Match> out = new ArrayList<>();
        if (query == null) return out;
        String q = query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) return out;
        String qd = digits(q);
        boolean digitsOnly = qd.length() == q.replace(" ", "").replace("+", "").length();
        for (Contact ct : contacts) {
            if (ct.numbers.isEmpty()) continue;
            int score = 0;
            Number hit = ct.numbers.get(0);
            if (!keypad || !digitsOnly) {
                if (ct.lowerName.startsWith(q)) score = 100;
                else if (ct.lowerName.contains(" " + q)) score = 90;
                else if (ct.lowerName.contains(q)) score = 60;
            }
            if (qd.length() >= 2) {
                for (int i = 0; i < ct.digits.size(); i++) {
                    String d = ct.digits.get(i);
                    int s = d.startsWith(qd) || key(d).startsWith(qd) ? 95 : d.contains(qd) ? 70 : 0;
                    if (s > score) {
                        score = s;
                        hit = ct.numbers.get(i);
                    }
                }
                if (keypad && digitsOnly) {
                    int s = 0;
                    if (ct.t9Full.startsWith(qd)) s = 85;
                    else for (String w : ct.t9Words) if (w.startsWith(qd)) { s = 80; break; }
                    if (s > score) {
                        score = s;
                        hit = ct.numbers.get(0);
                    }
                }
            }
            if (score > 0) out.add(new Match(ct, hit, score + (ct.starred ? 3 : 0)));
        }
        Collections.sort(out, (a, b) -> b.score != a.score ? b.score - a.score : a.contact.name.compareToIgnoreCase(b.contact.name));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ----------------------------------------------------------------- photos

    private static final LruCache<String, Bitmap> PHOTOS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap v) {
            return v.getByteCount();
        }
    };
    private static final Map<String, Boolean> MISSING = Collections.synchronizedMap(new HashMap<>());

    static void forgetPhoto(String uri) {
        if (uri == null) return;
        PHOTOS.remove(uri);
        MISSING.remove(uri);
    }

    static void clearPhotos() {
        PHOTOS.evictAll();
        MISSING.clear();
    }

    /** Loads a photo off the main thread; only shows it if the avatar still belongs to that person. */
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
            Bitmap b = loadBitmap(app, uri);
            if (b == null) MISSING.put(uri, true);
            else PHOTOS.put(uri, b);
            MAIN.post(() -> {
                if (b != null && uri.equals(view.getBoundKey())) view.setPhoto(b);
            });
        });
    }

    static Bitmap loadBitmap(Context ctx, String uri) {
        if (uri == null) return null;
        try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
            return in == null ? null : BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }
}
