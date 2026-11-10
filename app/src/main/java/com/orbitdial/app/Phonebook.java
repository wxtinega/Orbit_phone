package com.orbitdial.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.BlockedNumberContract;
import android.provider.BlockedNumberContract.BlockedNumbers;
import android.provider.CallLog;
import android.provider.ContactsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Changes to the phone's own contacts, block list and call history. */
final class Phonebook {
    private Phonebook() {}

    /** Thrown with a message that is safe to show to people. */
    static final class Problem extends Exception {
        Problem(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ photo

    /** Reads an image the user picked, fixes rotation, crops it square at 720px. */
    static Bitmap preparePhoto(Context ctx, Uri picked) throws Problem {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(picked)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap src;
            try (InputStream in = ctx.getContentResolver().openInputStream(picked)) {
                src = BitmapFactory.decodeStream(in, null, opts);
            }
            if (src == null) throw new Problem("That file isn't a photo T.I Dial can read.");
            int rotation = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(picked)) {
                if (in != null) {
                    int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    rotation = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                            : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                }
            } catch (Exception ignored) {
            }
            int side = Math.min(src.getWidth(), src.getHeight());
            float scale = Math.min(1f, 720f / side);
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            return Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side, m, true);
        } catch (Problem p) {
            throw p;
        } catch (OutOfMemoryError | Exception e) {
            throw new Problem("Couldn't open that photo. Try a different one.");
        }
    }

    private static long rawContactId(Context ctx, long contactId) {
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID},
                ContactsContract.RawContacts.CONTACT_ID + "=? AND " + ContactsContract.RawContacts.DELETED + "=0",
                new String[]{String.valueOf(contactId)}, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    static void setPhoto(Context ctx, long contactId, Bitmap photo) throws Problem {
        long raw = rawContactId(ctx, contactId);
        if (raw < 0) throw new Problem("Couldn't find this contact to update.");
        Uri uri = Uri.withAppendedPath(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, raw),
                ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
        try (AssetFileDescriptor fd = ctx.getContentResolver().openAssetFileDescriptor(uri, "rw")) {
            if (fd == null) throw new Problem("Your contacts app didn't accept the photo.");
            try (OutputStream out = fd.createOutputStream()) {
                photo.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
        } catch (SecurityException e) {
            throw new Problem("T.I Dial needs permission to edit contacts.");
        } catch (Problem p) {
            throw p;
        } catch (Exception e) {
            throw new Problem("Couldn't save the photo. Please try again.");
        }
    }

    static void removePhoto(Context ctx, long contactId) throws Problem {
        long raw = rawContactId(ctx, contactId);
        if (raw < 0) throw new Problem("Couldn't find this contact to update.");
        try {
            ctx.getContentResolver().delete(ContactsContract.Data.CONTENT_URI,
                    ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                    new String[]{String.valueOf(raw), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE});
        } catch (SecurityException e) {
            throw new Problem("T.I Dial needs permission to edit contacts.");
        }
    }

    // ------------------------------------------------------------ other edits

    static void setRingtone(Context ctx, long contactId, String ringtoneUri) throws Problem {
        ContentValues v = new ContentValues();
        v.put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri);
        update(ctx, contactId, v);
    }

    static void setStarred(Context ctx, long contactId, boolean starred) throws Problem {
        ContentValues v = new ContentValues();
        v.put(ContactsContract.Contacts.STARRED, starred ? 1 : 0);
        update(ctx, contactId, v);
    }

    private static void update(Context ctx, long contactId, ContentValues v) throws Problem {
        try {
            ctx.getContentResolver().update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), v, null, null);
        } catch (SecurityException e) {
            throw new Problem("T.I Dial needs permission to edit contacts.");
        } catch (RuntimeException e) {
            throw new Problem("Couldn't update this contact. Please try again.");
        }
    }

    // ------------------------------------------------------------------ block

    static boolean canBlock(Context ctx) {
        try {
            return BlockedNumberContract.canCurrentUserBlockNumbers(ctx);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isBlocked(Context ctx, String number) {
        try {
            return BlockedNumberContract.isBlocked(ctx, number);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void block(Context ctx, String number) throws Problem {
        ContentValues v = new ContentValues();
        v.put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number);
        try {
            ctx.getContentResolver().insert(BlockedNumbers.CONTENT_URI, v);
        } catch (SecurityException e) {
            throw new Problem("Make T.I Dial your phone app to block numbers.");
        } catch (RuntimeException e) {
            throw new Problem("Couldn't block this number. Please try again.");
        }
    }

    static void unblock(Context ctx, String number) throws Problem {
        try {
            BlockedNumberContract.unblock(ctx, number);
        } catch (SecurityException e) {
            throw new Problem("Make T.I Dial your phone app to manage blocked numbers.");
        } catch (RuntimeException e) {
            throw new Problem("Couldn't unblock this number. Please try again.");
        }
    }

    static List<String> blocked(Context ctx) throws Problem {
        List<String> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(BlockedNumbers.CONTENT_URI,
                new String[]{BlockedNumbers.COLUMN_ORIGINAL_NUMBER}, null, null, null)) {
            if (c != null) while (c.moveToNext()) out.add(c.getString(0));
        } catch (SecurityException e) {
            throw new Problem("Make T.I Dial your phone app to see blocked numbers.");
        } catch (RuntimeException e) {
            throw new Problem("Couldn't load blocked numbers.");
        }
        return out;
    }

    // ---------------------------------------------------------------- history

    static void deleteCalls(Context ctx, List<Long> ids) throws Problem {
        if (ids.isEmpty()) return;
        StringBuilder in = new StringBuilder();
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
            args[i] = String.valueOf(ids.get(i));
        }
        try {
            ctx.getContentResolver().delete(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID + " IN (" + in + ")", args);
        } catch (SecurityException e) {
            throw new Problem("T.I Dial needs permission to edit call history.");
        } catch (RuntimeException e) {
            throw new Problem("Couldn't delete that. Please try again.");
        }
    }

    // --------------------------------------------------------------- WhatsApp

    static final String WA = "com.whatsapp", WA_BIZ = "com.whatsapp.w4b";

    static String whatsAppPackage(Context ctx) {
        for (String p : new String[]{WA, WA_BIZ}) {
            try {
                ctx.getPackageManager().getPackageInfo(p, 0);
                return p;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** WhatsApp adds its own "voice call" rows to contacts it knows; those open a WhatsApp call directly. */
    static long whatsAppCallDataId(Context ctx, long contactId, String number) {
        if (contactId < 0) return -1;
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data._ID, ContactsContract.Data.DATA1},
                ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                new String[]{String.valueOf(contactId), "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"}, null)) {
            if (c == null) return -1;
            long first = -1;
            while (c.moveToNext()) {
                if (first < 0) first = c.getLong(0);
                String jid = c.getString(1);
                if (jid != null && number != null && Data.sameNumber(jid, number)) return c.getLong(0);
            }
            return first;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
