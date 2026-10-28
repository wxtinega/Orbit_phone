package com.orbitdial.app;

import android.Manifest;
import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements OrbitDialView.Listener {

    private static final int REQ_PERMS = 41;
    private static final int REQ_CALL = 42;
    private static final String[] DATA_PERMS = {
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE};
    private static final int TAB_KEYPAD = 0, TAB_RECENTS = 1, TAB_CONTACTS = 2;

    private final StringBuilder number = new StringBuilder();
    private List<Data.Contact> contacts = new ArrayList<>();
    private List<Data.Call> calls = new ArrayList<>();
    private Stores.GestureStore gestures;
    private SharedPreferences prefs;
    private ToneGenerator tones;
    private String countryIso;
    private String pendingCallNumber, pendingCallLabel;
    private String pickForGesture;          // a new gesture waiting for the user to choose a contact
    private Data.Contact assignContact;     // a contact waiting for the user to draw a gesture
    private Data.Phone assignPhone;
    private int tab = TAB_KEYPAD;
    private boolean missedOnly;
    private String query = "";

    private LivelyBackground bg;
    private final View[] pages = new View[3];
    private final TextView[] tabLabels = new TextView[3];
    private final ImageView[] tabIcons = new ImageView[3];
    private View tabIndicator;
    private FrameLayout tabBar;
    private TextView recentsBadge;

    // Keypad
    private TextView numberView, matchView, hintView;
    private LinearLayout suggestBox, keypadActions;
    private TextView addContactPill;
    private OrbitDialView dial;

    // Recents
    private final RecentsAdapter recentsAdapter = new RecentsAdapter();
    private ListView recentsList;
    private TextView statToday, statTalk, statMissed, statTop, chipAll, chipMissed;
    private LinearLayout recentsEmpty;
    private TextView recentsEmptyText, recentsEmptyBtn;

    // Contacts
    private final ContactsAdapter contactsAdapter = new ContactsAdapter();
    private ListView contactsList;
    private EditText searchBox;
    private LinearLayout favRow, favSection;
    private TextView contactsSub, pickBanner;
    private LinearLayout contactsEmpty;
    private TextView contactsEmptyText, contactsEmptyBtn;

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        gestures = new Stores.GestureStore(this);
        countryIso = detectCountry();
        try {
            tones = new ToneGenerator(AudioManager.STREAM_DTMF, 60);
        } catch (RuntimeException e) {
            tones = null;
        }

        setContentView(buildRoot());

        if (state != null) {
            number.append(state.getString("number", ""));
            tab = state.getInt("tab", TAB_KEYPAD);
        } else {
            readDialIntent(getIntent());
        }
        showTab(tab, false);
        refreshKeypad();
        refreshRecents(false);
        refreshContacts();
        askForPermissionsOnce();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadData(); // picks up the call you just made, new contacts, etc.
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (readDialIntent(intent)) {
            showTab(TAB_KEYPAD, true);
            refreshKeypad();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("number", number.toString());
        out.putInt("tab", tab);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tones != null) tones.release();
    }

    @Override
    public void onBackPressed() {
        if (pickForGesture != null) {
            cancelPick();
        } else if (tab != TAB_KEYPAD) {
            showTab(TAB_KEYPAD, true);
        } else {
            super.onBackPressed();
        }
    }

    private boolean readDialIntent(Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        if (data != null && "tel".equalsIgnoreCase(data.getScheme())) {
            String n = data.getSchemeSpecificPart();
            if (n != null) {
                number.setLength(0);
                number.append(n.replaceAll("[^0-9+*#]", ""));
                return true;
            }
        }
        return false;
    }

    // ================================================================= permissions

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void askForPermissionsOnce() {
        if (prefs.getBoolean("asked_perms", false)) return;
        List<String> missing = new ArrayList<>();
        for (String p : DATA_PERMS) if (!has(p)) missing.add(p);
        if (!missing.isEmpty()) {
            prefs.edit().putBoolean("asked_perms", true).apply();
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    /** Ask again, or send the user to Settings if Android won't show the prompt any more. */
    private void requestOrOpenSettings(String perm) {
        if (shouldShowRequestPermissionRationale(perm) || !prefs.getBoolean("asked_" + perm, false)) {
            prefs.edit().putBoolean("asked_" + perm, true).apply();
            requestPermissions(new String[]{perm}, REQ_PERMS);
        } else {
            toast("Turn on the permission under Permissions");
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CALL && pendingCallNumber != null) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            startCall(pendingCallNumber, granted ? Intent.ACTION_CALL : Intent.ACTION_DIAL);
            pendingCallNumber = null;
            pendingCallLabel = null;
        }
        reloadData();
    }

    // ======================================================================= data

    private void reloadData() {
        final boolean canContacts = has(Manifest.permission.READ_CONTACTS);
        final boolean canCalls = has(Manifest.permission.READ_CALL_LOG);
        final Context app = getApplicationContext();
        Data.IO.execute(() -> {
            List<Data.Contact> c = canContacts ? Data.loadContacts(app) : new ArrayList<>();
            List<Data.Call> l = canCalls ? Data.loadCalls(app, 400) : new ArrayList<>();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                contacts = c;
                calls = l;
                refreshRecents(false);
                refreshContacts();
                refreshKeypad();
            });
        });
    }

    // ======================================================================= shell

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        bg = new LivelyBackground(this);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout pager = new FrameLayout(this);
        col.addView(pager, new LinearLayout.LayoutParams(-1, 0, 1f));
        pages[TAB_KEYPAD] = buildKeypad();
        pages[TAB_RECENTS] = buildRecents();
        pages[TAB_CONTACTS] = buildContacts();
        for (View p : pages) {
            p.setVisibility(View.GONE);
            pager.addView(p, new FrameLayout.LayoutParams(-1, -1));
        }
        col.addView(buildTabBar());
        return root;
    }

    private View buildTabBar() {
        tabBar = new FrameLayout(this);
        tabBar.setBackground(Ui.round(0xE6161C33, dp(30)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(64));
        lp.setMargins(dp(14), dp(4), dp(14), dp(14));
        tabBar.setLayoutParams(lp);

        tabIndicator = new View(this);
        tabIndicator.setBackground(Ui.round(0x557C5CFF, dp(24)));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(0, -1);
        ilp.setMargins(0, dp(6), 0, dp(6));
        tabBar.addView(tabIndicator, ilp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.addView(row, new FrameLayout.LayoutParams(-1, -1));

        String[] labels = {"Keypad", "Recents", "Contacts"};
        int[] icons = {R.drawable.ic_dialpad, R.drawable.ic_history, R.drawable.ic_person};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                showTab(index, true);
            });

            FrameLayout iconWrap = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            iconWrap.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            if (i == TAB_RECENTS) {
                recentsBadge = Ui.text(this, "", 10, Color.WHITE, true);
                recentsBadge.setGravity(Gravity.CENTER);
                recentsBadge.setBackground(Ui.round(Ui.RED, dp(9)));
                recentsBadge.setPadding(dp(5), 0, dp(5), 0);
                recentsBadge.setMinWidth(dp(18));
                recentsBadge.setVisibility(View.GONE);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP | Gravity.END);
                iconWrap.addView(recentsBadge, blp);
            }
            item.addView(iconWrap, new LinearLayout.LayoutParams(dp(40), dp(26)));

            TextView label = Ui.text(this, labels[i], 11, Ui.MUTED, true);
            label.setPadding(0, dp(2), 0, 0);
            item.addView(label);
            tabIcons[i] = icon;
            tabLabels[i] = label;
            row.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        tabBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = (r - l) / 3;
            ViewGroup.LayoutParams p = tabIndicator.getLayoutParams();
            if (p.width != w - dp(12)) {
                p.width = w - dp(12);
                tabIndicator.setLayoutParams(p);
            }
            tabIndicator.setTranslationX(tab * w + dp(6));
        });
        return tabBar;
    }

    private void showTab(int t, boolean animate) {
        View in = pages[t];
        View out = pages[tab];
        if (animate && t != tab) {
            int dir = t > tab ? 1 : -1;
            in.setAlpha(0f);
            in.setTranslationX(dir * dp(28));
            in.setVisibility(View.VISIBLE);
            in.animate().alpha(1f).translationX(0f).setDuration(240).start();
            out.animate().alpha(0f).translationX(-dir * dp(28)).setDuration(160).withEndAction(() -> {
                if (pages[tab] != out) out.setVisibility(View.GONE);
                out.setAlpha(1f);
                out.setTranslationX(0f);
            }).start();
        } else {
            for (View p : pages) p.setVisibility(p == in ? View.VISIBLE : View.GONE);
        }
        tab = t;
        int w = tabBar.getWidth() / 3;
        if (w > 0) tabIndicator.animate().translationX(t * w + dp(6)).setDuration(260).start();
        for (int i = 0; i < 3; i++) {
            int color = i == t ? Color.WHITE : Ui.MUTED;
            tabLabels[i].setTextColor(color);
            tabIcons[i].setImageTintList(ColorStateList.valueOf(color));
        }
        if (t != TAB_CONTACTS) hideKeyboard();
        if (t == TAB_RECENTS) {
            prefs.edit().putLong("recents_seen", System.currentTimeMillis()).apply();
            updateBadge();
            recentsList.scheduleLayoutAnimation();
        }
        if (t == TAB_CONTACTS) contactsList.scheduleLayoutAnimation();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchBox != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    private LinearLayout page() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(22), dp(20), 0);
        return v;
    }

    private LinearLayout titleRow(String title, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, title, 28, Color.WHITE, true);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) row.addView(right);
        return row;
    }

    private ImageView iconButton(int res, int tint, int bgColor, float sizeDp, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(tint));
        int pad = dp(sizeDp * 0.25f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(Ui.rippleBg(bgColor, dp(sizeDp / 2f)));
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private LayoutAnimationController listAnimation() {
        LayoutAnimationController c = new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in), 0.06f);
        c.setOrder(LayoutAnimationController.ORDER_NORMAL);
        return c;
    }

    private LinearLayout emptyState(String msg, String button, View.OnClickListener click, TextView[] outRefs) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView t = Ui.text(this, msg, 15, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView b = Ui.pill(this, button, Ui.ACCENT, click);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(16);
        box.addView(b, blp);
        outRefs[0] = t;
        outRefs[1] = b;
        return box;
    }

    // ===================================================================== keypad

    private View buildKeypad() {
        LinearLayout v = page();
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        v.setLayoutTransition(lt);

        TextView gesturesPill = Ui.pill(this, "Gestures", 0x33FFFFFF, x -> showGestureManager());
        v.addView(titleRow("Orbit Dial", gesturesPill));

        LinearLayout numberRow = new LinearLayout(this);
        numberRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(18);
        v.addView(numberRow, rowLp);

        numberRow.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        numberView = Ui.text(this, "", 34, Color.WHITE, false);
        numberView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        numberView.setGravity(Gravity.CENTER);
        numberView.setSingleLine(true);
        numberView.setEllipsize(TextUtils.TruncateAt.START);
        numberView.setHint("Enter a number");
        numberView.setHintTextColor(0x55FFFFFF);
        numberView.setOnLongClickListener(x -> {
            copy(number.toString());
            return true;
        });
        numberRow.addView(numberView, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView back = iconButton(R.drawable.ic_backspace, Ui.MUTED, 0x00000000, 44, x -> {
            if (number.length() > 0) {
                number.deleteCharAt(number.length() - 1);
                x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                refreshKeypad();
            }
        });
        back.setOnLongClickListener(x -> {
            number.setLength(0);
            x.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            refreshKeypad();
            return true;
        });
        numberRow.addView(back);

        matchView = Ui.text(this, "", 14, Ui.CYAN, true);
        matchView.setGravity(Gravity.CENTER);
        v.addView(matchView);

        hintView = Ui.text(this, "", 12, Ui.MUTED, false);
        hintView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(2);
        v.addView(hintView, hlp);

        suggestBox = new LinearLayout(this);
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(8);
        v.addView(suggestBox, slp);

        keypadActions = new LinearLayout(this);
        keypadActions.setGravity(Gravity.CENTER);
        addContactPill = Ui.pill(this, "+ Add contact", 0x33FFFFFF, x -> addContact(number.toString()));
        TextView smsPill = Ui.pill(this, "Message", 0x33FFFFFF, x -> sms(number.toString()));
        keypadActions.addView(addContactPill);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.leftMargin = dp(10);
        keypadActions.addView(smsPill, mlp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(8);
        v.addView(keypadActions, alp);

        dial = new OrbitDialView(this);
        dial.setColors(Ui.ACCENT, Ui.GREEN);
        dial.setListener(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        dlp.topMargin = dp(6);
        v.addView(dial, dlp);
        return v;
    }

    private void refreshKeypad() {
        if (numberView == null) return;
        String n = number.toString();
        numberView.setText(format(n));
        Data.Contact exact = n.length() >= 3 ? Data.findByNumber(contacts, n) : null;
        matchView.setText(exact != null ? exact.name : "");
        matchView.setVisibility(exact != null ? View.VISIBLE : View.GONE);
        dial.setReady(n.length() > 0);
        bg.setMood(n.isEmpty() ? Ui.ACCENT : 0xFF16A34A);

        suggestBox.removeAllViews();
        if (exact == null && n.length() >= 2) {
            for (Data.Match m : Data.search(contacts, n, 2)) suggestBox.addView(suggestionRow(m));
        }
        keypadActions.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        addContactPill.setVisibility(exact == null && !n.contains("*") && !n.contains("#") ? View.VISIBLE : View.GONE);

        if (assignContact != null) {
            hintView.setText("Draw a path across 3+ keys for " + assignContact.name);
            hintView.setTextColor(Ui.CYAN);
        } else if (n.isEmpty()) {
            int count = gestures.all().size();
            hintView.setText(count == 0 ? "Tip: drag across 3+ keys to create a Gesture Speed Dial"
                    : count + (count == 1 ? " gesture ready" : " gestures ready") + " · type to search contacts");
            hintView.setTextColor(Ui.MUTED);
        } else {
            hintView.setText("");
        }
        hintView.setVisibility(hintView.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private View suggestionRow(Data.Match m) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackground(Ui.rippleBg(0x26FFFFFF, dp(16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        AvatarView av = new AvatarView(this);
        av.set(m.contact.name, m.contact.lookupKey);
        Data.bindPhoto(this, av, m.contact.photoUri);
        row.addView(av, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        texts.addView(Ui.text(this, m.contact.name, 15, Color.WHITE, true));
        String sub = (m.phone.label.isEmpty() ? "" : m.phone.label + " · ") + m.phone.number
                + (m.byName ? "  · name match" : "");
        texts.addView(Ui.text(this, sub, 12, Ui.MUTED, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(iconButton(R.drawable.ic_call, Color.WHITE, Ui.GREEN, 36,
                x -> placeCall(m.phone.number, m.contact.name)));
        row.setOnClickListener(x -> {
            number.setLength(0);
            number.append(Data.digits(m.phone.number).isEmpty() ? m.phone.number : m.phone.number.replaceAll("[^0-9+*#]", ""));
            refreshKeypad();
        });
        return row;
    }

    @Override
    public void onDigit(char key) {
        if (number.length() >= 30) return;
        number.append(key);
        playTone(key);
        refreshKeypad();
    }

    @Override
    public void onDigitLongPress(char key) {
        if (key == '0') {
            number.append('+');
            refreshKeypad();
        } else if (key == '1' && number.length() == 0) {
            toast("Voicemail: set it up in your carrier's dialer");
        }
    }

    @Override
    public void onCallPressed() {
        if (number.length() == 0) {
            if (!calls.isEmpty()) {
                number.append(calls.get(0).number);
                refreshKeypad();
                toast("Last number · tap CALL again");
            } else {
                toast("Type a number first");
            }
            return;
        }
        placeCall(number.toString(), null);
    }

    @Override
    public void onGesture(String pattern) {
        if (assignContact != null) {
            gestures.put(pattern, assignContact.name, assignPhone.number);
            toast(Stores.prettify(pattern) + " now calls " + assignContact.name);
            assignContact = null;
            assignPhone = null;
            refreshKeypad();
            return;
        }
        Stores.Gesture g = gestures.get(pattern);
        if (g != null) {
            number.setLength(0);
            number.append(g.number);
            refreshKeypad();
            toast("Gesture " + g.prettyPattern() + " → " + g.name);
            placeCall(g.number, g.name);
            return;
        }
        List<String> options = new ArrayList<>();
        options.add("Choose a contact");
        if (number.length() > 0) options.add("Use " + format(number.toString()));
        options.add("Type a number");
        new AlertDialog.Builder(this)
                .setTitle("New gesture  " + Stores.prettify(pattern))
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    String choice = options.get(which);
                    if (choice.startsWith("Choose")) {
                        startPick(pattern);
                    } else if (choice.startsWith("Use")) {
                        String n = number.toString();
                        Data.Contact ct = Data.findByNumber(contacts, n);
                        gestures.put(pattern, ct != null ? ct.name : format(n), n);
                        toast("Saved " + Stores.prettify(pattern));
                        refreshKeypad();
                    } else {
                        typeNumberForGesture(pattern);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void typeNumberForGesture(String pattern) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = new EditText(this);
        name.setHint("Name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(name);
        EditText num = new EditText(this);
        num.setHint("Phone number");
        num.setInputType(InputType.TYPE_CLASS_PHONE);
        box.addView(num);
        new AlertDialog.Builder(this)
                .setTitle("Gesture  " + Stores.prettify(pattern))
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = num.getText().toString().replaceAll("[^0-9+*#]", "");
                    if (n.isEmpty()) {
                        toast("Not saved: the number was empty");
                        return;
                    }
                    String label = name.getText().toString().trim();
                    gestures.put(pattern, label.isEmpty() ? format(n) : label, n);
                    toast("Saved " + Stores.prettify(pattern));
                    refreshKeypad();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showGestureManager() {
        List<Stores.Gesture> list = gestures.all();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Gesture Speed Dial")
                    .setMessage("Drag your finger across three or more keys on the dial without lifting it, "
                            + "for example 1 → 5 → 9. When you let go, link that path to a contact.\n\n"
                            + "From then on, drawing the path calls them instantly. You can also open any "
                            + "contact and tap \"Assign gesture\".")
                    .setPositiveButton("Got it", null)
                    .show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Stores.Gesture g = list.get(i);
            items[i] = g.prettyPattern() + "    " + g.name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Your gestures")
                .setItems(items, (d, which) -> {
                    Stores.Gesture g = list.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(g.prettyPattern() + "  →  " + g.name)
                            .setMessage(format(g.number))
                            .setPositiveButton("Call", (d2, w) -> placeCall(g.number, g.name))
                            .setNeutralButton("Delete", (d2, w) -> {
                                gestures.remove(g.pattern);
                                toast("Deleted");
                                refreshKeypad();
                            })
                            .setNegativeButton("Close", null)
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void playTone(char key) {
        if (tones == null) return;
        int tone;
        switch (key) {
            case '0': tone = ToneGenerator.TONE_DTMF_0; break;
            case '1': tone = ToneGenerator.TONE_DTMF_1; break;
            case '2': tone = ToneGenerator.TONE_DTMF_2; break;
            case '3': tone = ToneGenerator.TONE_DTMF_3; break;
            case '4': tone = ToneGenerator.TONE_DTMF_4; break;
            case '5': tone = ToneGenerator.TONE_DTMF_5; break;
            case '6': tone = ToneGenerator.TONE_DTMF_6; break;
            case '7': tone = ToneGenerator.TONE_DTMF_7; break;
            case '8': tone = ToneGenerator.TONE_DTMF_8; break;
            case '9': tone = ToneGenerator.TONE_DTMF_9; break;
            case '*': tone = ToneGenerator.TONE_DTMF_S; break;
            case '#': tone = ToneGenerator.TONE_DTMF_P; break;
            default: return;
        }
        tones.startTone(tone, 110);
    }

    // ==================================================================== recents

    private View buildRecents() {
        LinearLayout v = page();
        v.addView(titleRow("Recents", null));

        // Insights card: today's activity at a glance.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Ui.round(0x33222B4D, dp(20)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(14);
        v.addView(card, clp);
        LinearLayout stats = new LinearLayout(this);
        card.addView(stats);
        statToday = stat(stats, "Calls today", Color.WHITE);
        statTalk = stat(stats, "Talk time", Ui.CYAN);
        statMissed = stat(stats, "Missed", Ui.RED);
        statTop = Ui.text(this, "", 12, Ui.MUTED, false);
        statTop.setPadding(0, dp(10), 0, 0);
        card.addView(statTop);

        LinearLayout chips = new LinearLayout(this);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, -2);
        chlp.topMargin = dp(12);
        chlp.bottomMargin = dp(4);
        v.addView(chips, chlp);
        chipAll = Ui.pill(this, "All", Ui.ACCENT, x -> {
            missedOnly = false;
            refreshRecents(true);
        });
        chipMissed = Ui.pill(this, "Missed", 0x33FFFFFF, x -> {
            missedOnly = true;
            refreshRecents(true);
        });
        chips.addView(chipAll);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.leftMargin = dp(8);
        chips.addView(chipMissed, mlp);

        FrameLayout frame = new FrameLayout(this);
        v.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        recentsList = makeList();
        recentsList.setAdapter(recentsAdapter);
        frame.addView(recentsList, new FrameLayout.LayoutParams(-1, -1));
        TextView[] refs = new TextView[2];
        recentsEmpty = emptyState("", "Allow access", x -> requestOrOpenSettings(Manifest.permission.READ_CALL_LOG), refs);
        recentsEmptyText = refs[0];
        recentsEmptyBtn = refs[1];
        frame.addView(recentsEmpty, new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    private TextView stat(LinearLayout parent, String label, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView value = Ui.text(this, "–", 22, color, true);
        col.addView(value);
        col.addView(Ui.text(this, label, 12, Ui.MUTED, false));
        parent.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return value;
    }

    private ListView makeList() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setSelector(new ColorDrawable(Color.TRANSPARENT));
        l.setCacheColorHint(Color.TRANSPARENT);
        l.setVerticalScrollBarEnabled(false);
        l.setClipToPadding(false);
        l.setPadding(0, dp(4), 0, dp(12));
        l.setLayoutAnimation(listAnimation());
        return l;
    }

    private void refreshRecents(boolean animate) {
        if (recentsList == null) return;
        boolean allowed = has(Manifest.permission.READ_CALL_LOG);
        chipAll.setBackground(Ui.rippleBg(missedOnly ? 0x33FFFFFF : Ui.ACCENT, dp(20)));
        chipMissed.setBackground(Ui.rippleBg(missedOnly ? Ui.RED : 0x33FFFFFF, dp(20)));

        // Group consecutive calls with the same number, like other dialers do.
        List<RecentRow> rows = new ArrayList<>();
        for (Data.Call c : calls) {
            if (missedOnly && !c.missed()) continue;
            RecentRow last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null && Data.sameNumber(last.call.number, c.number) && last.call.missed() == c.missed()) {
                last.count++;
            } else {
                rows.add(new RecentRow(c));
            }
        }
        recentsAdapter.rows = rows;
        recentsAdapter.notifyDataSetChanged();
        if (animate) recentsList.scheduleLayoutAnimation();

        if (!allowed) {
            recentsEmptyText.setText("Orbit Dial needs permission to show your call history.");
            recentsEmptyBtn.setVisibility(View.VISIBLE);
        } else {
            recentsEmptyText.setText(missedOnly ? "No missed calls. Nice." : "No calls yet.");
            recentsEmptyBtn.setVisibility(View.GONE);
        }
        recentsEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        updateStats();
        updateBadge();
    }

    private void updateStats() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        long weekStart = dayStart - 6L * 24 * 3600 * 1000;
        int today = 0, missed = 0;
        long talk = 0;
        Map<String, Integer> freq = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Data.Call c : calls) {
            if (c.date >= dayStart) {
                today++;
                talk += c.duration;
                if (c.missed()) missed++;
            }
            if (c.date >= weekStart && !c.missed() && !c.number.isEmpty()) {
                String d = Data.digits(c.number);
                String key = d.length() > 9 ? d.substring(d.length() - 9) : d;
                freq.put(key, freq.getOrDefault(key, 0) + 1);
                names.put(key, c.title());
            }
        }
        statToday.setText(String.valueOf(today));
        statTalk.setText(talk == 0 ? "0m" : Ui.duration(talk));
        statMissed.setText(String.valueOf(missed));
        String topKey = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                topKey = e.getKey();
            }
        }
        statTop.setText(topKey == null ? "Your call insights appear here."
                : "Most talked to this week: " + names.get(topKey) + " (" + best + (best == 1 ? " call)" : " calls)"));
    }

    private void updateBadge() {
        long seen = prefs.getLong("recents_seen", 0);
        int n = 0;
        for (Data.Call c : calls) {
            if (c.date <= seen) break;
            if (c.missed()) n++;
        }
        recentsBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        recentsBadge.setVisibility(n > 0 && tab != TAB_RECENTS ? View.VISIBLE : View.GONE);
    }

    private static final class RecentRow {
        final Data.Call call;
        int count = 1;

        RecentRow(Data.Call call) {
            this.call = call;
        }
    }

    private final class RecentsAdapter extends BaseAdapter {
        List<RecentRow> rows = new ArrayList<>();

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            RowHolder h;
            if (convert == null) {
                h = new RowHolder(true);
                convert = h.root;
            } else {
                h = (RowHolder) convert.getTag();
            }
            RecentRow r = rows.get(pos);
            Data.Call c = r.call;
            Data.Contact ct = Data.findByNumber(contacts, c.number);
            String title = ct != null ? ct.name : c.title();
            h.avatar.set(title, ct != null ? ct.lookupKey : c.number);
            Data.bindPhoto(MainActivity.this, h.avatar, ct != null ? ct.photoUri : null);
            h.title.setText(r.count > 1 ? title + "  (" + r.count + ")" : title);
            h.title.setTextColor(c.missed() ? Ui.RED : Color.WHITE);

            int icon, tint;
            if (c.missed()) {
                icon = R.drawable.ic_call_missed;
                tint = Ui.RED;
            } else if (c.type == android.provider.CallLog.Calls.OUTGOING_TYPE) {
                icon = R.drawable.ic_call_out;
                tint = Ui.BLUE;
            } else {
                icon = R.drawable.ic_call_in;
                tint = Ui.GREEN;
            }
            h.typeIcon.setImageResource(icon);
            h.typeIcon.setImageTintList(ColorStateList.valueOf(tint));
            h.typeIcon.setVisibility(View.VISIBLE);
            StringBuilder sub = new StringBuilder();
            if (ct == null && c.name != null && !c.name.isEmpty()) sub.append(c.number).append(" · ");
            else if (c.numberLabel != null && !c.numberLabel.isEmpty()) sub.append(c.numberLabel).append(" · ");
            sub.append(Ui.ago(c.date));
            String dur = Ui.duration(c.duration);
            if (!dur.isEmpty()) sub.append(" · ").append(dur);
            h.sub.setText(sub);
            h.action.setOnClickListener(x -> placeCall(c.number, title));
            h.action.setVisibility(c.number.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            h.header.setVisibility(View.GONE);
            h.line.setOnClickListener(x -> showCallActions(c, ct, title));
            h.line.setOnLongClickListener(x -> {
                copy(c.number);
                return true;
            });
            return convert;
        }
    }

    /** One list row: avatar, two lines of text, and a round action button. Shared by both lists. */
    private final class RowHolder {
        final LinearLayout root;
        final TextView header, title, sub;
        final AvatarView avatar;
        final ImageView typeIcon, action;

        RowHolder(boolean callButton) {
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setTag(this);
            header = Ui.text(MainActivity.this, "", 13, Ui.ACCENT, true);
            header.setPadding(dp(6), dp(14), 0, dp(4));
            root.addView(header);

            LinearLayout line = new LinearLayout(MainActivity.this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(6), dp(9), dp(4), dp(9));
            line.setBackground(Ui.rippleBg(0x00000000, dp(16)));
            line.setDuplicateParentStateEnabled(false);
            root.addView(line, new LinearLayout.LayoutParams(-1, -2));

            avatar = new AvatarView(MainActivity.this);
            line.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(14), 0, dp(8), 0);
            title = Ui.text(MainActivity.this, "", 16, Color.WHITE, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            LinearLayout subLine = new LinearLayout(MainActivity.this);
            subLine.setGravity(Gravity.CENTER_VERTICAL);
            typeIcon = new ImageView(MainActivity.this);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(14), dp(14));
            tlp.rightMargin = dp(4);
            subLine.addView(typeIcon, tlp);
            typeIcon.setVisibility(View.GONE);
            sub = Ui.text(MainActivity.this, "", 13, Ui.MUTED, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            subLine.addView(sub);
            texts.addView(subLine);
            line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            action = iconButton(callButton ? R.drawable.ic_call : R.drawable.ic_star,
                    callButton ? Ui.GREEN : 0xFFFBBF24, callButton ? 0x3322C55E : 0x00000000, 40, null);
            line.addView(action);
            // Clicks on the whole row go to the inner line so the ripple shows.
            root.setClickable(false);
            this.line = line;
        }

        final LinearLayout line;
    }

    private void showCallActions(Data.Call c, Data.Contact ct, String title) {
        if (c.number.isEmpty()) {
            toast("Private number");
            return;
        }
        List<String> opts = new ArrayList<>();
        opts.add("Call");
        opts.add("Send message");
        opts.add("Edit before calling");
        opts.add("Copy number");
        opts.add(ct != null ? "View contact" : "Add to contacts");
        opts.add("Assign gesture");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    switch (opts.get(which)) {
                        case "Call": placeCall(c.number, title); break;
                        case "Send message": sms(c.number); break;
                        case "Edit before calling":
                            number.setLength(0);
                            number.append(c.number.replaceAll("[^0-9+*#]", ""));
                            showTab(TAB_KEYPAD, true);
                            refreshKeypad();
                            break;
                        case "Copy number": copy(c.number); break;
                        case "View contact": showContactSheet(ct); break;
                        case "Add to contacts": addContact(c.number); break;
                        case "Assign gesture": {
                            Data.Contact target = ct != null ? ct : new Data.Contact(-1, null, title);
                            beginAssign(target, new Data.Phone(c.number, ""));
                            break;
                        }
                        default: break;
                    }
                })
                .show();
    }

    // =================================================================== contacts

    private View buildContacts() {
        LinearLayout v = page();
        ImageView add = iconButton(R.drawable.ic_add, Color.WHITE, Ui.ACCENT, 42, x -> addContact(""));
        v.addView(titleRow("Contacts", add));
        contactsSub = Ui.text(this, "", 13, Ui.MUTED, false);
        v.addView(contactsSub);

        pickBanner = Ui.text(this, "", 14, Color.WHITE, true);
        pickBanner.setPadding(dp(16), dp(12), dp(16), dp(12));
        pickBanner.setBackground(Ui.rippleBg(Ui.ACCENT, dp(16)));
        pickBanner.setOnClickListener(x -> cancelPick());
        pickBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(10);
        v.addView(pickBanner, plp);

        searchBox = new EditText(this);
        searchBox.setHint("Search name or number");
        searchBox.setHintTextColor(0x66FFFFFF);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setSingleLine(true);
        searchBox.setBackground(Ui.round(0x33222B4D, dp(22)));
        searchBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        Drawable s = getDrawable(R.drawable.ic_search);
        if (s != null) {
            s = s.mutate();
            s.setTint(Ui.MUTED);
            s.setBounds(0, 0, dp(20), dp(20));
            searchBox.setCompoundDrawablesRelative(s, null, null, null);
            searchBox.setCompoundDrawablePadding(dp(10));
        }
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}

            @Override
            public void onTextChanged(CharSequence a, int b, int c, int d) {}

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString().trim();
                refreshContacts();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(12);
        v.addView(searchBox, slp);

        favSection = new LinearLayout(this);
        favSection.setOrientation(LinearLayout.VERTICAL);
        TextView favTitle = Ui.text(this, "Favourites", 13, Ui.ACCENT, true);
        favTitle.setPadding(dp(6), dp(14), 0, dp(6));
        favSection.addView(favTitle);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        favRow = new LinearLayout(this);
        hs.addView(favRow);
        favSection.addView(hs);
        v.addView(favSection);

        FrameLayout frame = new FrameLayout(this);
        v.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        contactsList = makeList();
        contactsList.setAdapter(contactsAdapter);
        contactsList.setFastScrollEnabled(true);
        frame.addView(contactsList, new FrameLayout.LayoutParams(-1, -1));
        TextView[] refs = new TextView[2];
        contactsEmpty = emptyState("", "Allow access", x -> requestOrOpenSettings(Manifest.permission.READ_CONTACTS), refs);
        contactsEmptyText = refs[0];
        contactsEmptyBtn = refs[1];
        frame.addView(contactsEmpty, new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    private void refreshContacts() {
        if (contactsList == null) return;
        boolean allowed = has(Manifest.permission.READ_CONTACTS);
        String q = query.toLowerCase(Locale.ROOT);
        String qd = Data.digits(query);
        List<Data.Contact> filtered = new ArrayList<>();
        for (Data.Contact c : contacts) {
            if (q.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(c);
                continue;
            }
            if (qd.length() >= 2) {
                for (String d : c.digitsList) {
                    if (d.contains(qd)) {
                        filtered.add(c);
                        break;
                    }
                }
            }
        }
        contactsAdapter.items = filtered;
        contactsAdapter.notifyDataSetChanged();
        contactsSub.setText(contacts.isEmpty() ? "" : contacts.size() + " people");

        favRow.removeAllViews();
        int favCount = 0;
        for (Data.Contact c : contacts) {
            if (!c.starred) continue;
            favCount++;
            favRow.addView(favItem(c));
        }
        favSection.setVisibility(favCount > 0 && q.isEmpty() ? View.VISIBLE : View.GONE);

        if (!allowed) {
            contactsEmptyText.setText("Orbit Dial needs permission to show your contacts.");
            contactsEmptyBtn.setVisibility(View.VISIBLE);
        } else {
            contactsEmptyText.setText(q.isEmpty() ? "No contacts with phone numbers yet." : "No matches for \"" + query + "\".");
            contactsEmptyBtn.setVisibility(View.GONE);
        }
        contactsEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View favItem(Data.Contact c) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(6), dp(4), dp(6), dp(4));
        item.setBackground(Ui.rippleBg(0x00000000, dp(16)));
        AvatarView av = new AvatarView(this);
        av.set(c.name, c.lookupKey);
        Data.bindPhoto(this, av, c.photoUri);
        item.addView(av, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView n = Ui.text(this, c.name.split("\\s+")[0], 12, Color.WHITE, false);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setMaxWidth(dp(70));
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(4), 0, 0);
        item.addView(n);
        item.setOnClickListener(x -> onContactTapped(c));
        item.setOnLongClickListener(x -> {
            placeCall(c.primaryNumber(), c.name);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), -2);
        item.setLayoutParams(lp);
        return item;
    }

    private final class ContactsAdapter extends BaseAdapter {
        List<Data.Contact> items = new ArrayList<>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return items.get(i).id;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            RowHolder h;
            if (convert == null) {
                h = new RowHolder(false);
                convert = h.root;
            } else {
                h = (RowHolder) convert.getTag();
            }
            Data.Contact c = items.get(pos);
            char sec = c.section();
            boolean showHeader = pos == 0 || items.get(pos - 1).section() != sec;
            h.header.setText(String.valueOf(sec));
            h.header.setVisibility(showHeader ? View.VISIBLE : View.GONE);
            h.avatar.set(c.name, c.lookupKey);
            Data.bindPhoto(MainActivity.this, h.avatar, c.photoUri);
            h.title.setText(c.name);
            h.title.setTextColor(Color.WHITE);
            Data.Phone p = c.phones.isEmpty() ? null : c.phones.get(0);
            String sub = p == null ? "" : (p.label.isEmpty() ? "" : p.label + " · ") + p.number;
            if (c.phones.size() > 1) sub += "  +" + (c.phones.size() - 1);
            h.sub.setText(sub);
            h.typeIcon.setVisibility(View.GONE);
            h.action.setVisibility(c.starred ? View.VISIBLE : View.GONE);
            h.action.setOnClickListener(null);
            h.action.setClickable(false);
            h.line.setOnClickListener(x -> onContactTapped(c));
            h.line.setOnLongClickListener(x -> {
                placeCall(c.primaryNumber(), c.name);
                return true;
            });
            return convert;
        }
    }

    private void onContactTapped(Data.Contact c) {
        if (pickForGesture != null) {
            choosePhone(c, phone -> {
                gestures.put(pickForGesture, c.name, phone.number);
                toast(Stores.prettify(pickForGesture) + " now calls " + c.name);
                cancelPick();
                showTab(TAB_KEYPAD, true);
                refreshKeypad();
            });
        } else {
            showContactSheet(c);
        }
    }

    private interface PhoneChoice {
        void chosen(Data.Phone p);
    }

    private void choosePhone(Data.Contact c, PhoneChoice cb) {
        if (c.phones.isEmpty()) {
            toast("No phone number saved");
            return;
        }
        if (c.phones.size() == 1) {
            cb.chosen(c.phones.get(0));
            return;
        }
        String[] items = new String[c.phones.size()];
        for (int i = 0; i < items.length; i++) {
            Data.Phone p = c.phones.get(i);
            items[i] = (p.label.isEmpty() ? "" : p.label + ": ") + p.number;
        }
        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setItems(items, (d, which) -> cb.chosen(c.phones.get(which)))
                .show();
    }

    private void startPick(String pattern) {
        pickForGesture = pattern;
        pickBanner.setText("Tap who " + Stores.prettify(pattern) + " should call   ·   tap here to cancel");
        pickBanner.setVisibility(View.VISIBLE);
        showTab(TAB_CONTACTS, true);
        if (!has(Manifest.permission.READ_CONTACTS)) requestOrOpenSettings(Manifest.permission.READ_CONTACTS);
    }

    private void cancelPick() {
        pickForGesture = null;
        pickBanner.setVisibility(View.GONE);
    }

    private void beginAssign(Data.Contact c, Data.Phone p) {
        assignContact = c;
        assignPhone = p;
        showTab(TAB_KEYPAD, true);
        refreshKeypad();
        toast("Now draw a path across 3 or more keys");
    }

    /** Bottom sheet with the contact's numbers and quick actions. */
    private void showContactSheet(Data.Contact c) {
        if (c == null) return;
        Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.setPadding(dp(22), dp(14), dp(22), dp(26));
        sheet.setBackground(Ui.round(Ui.SURFACE, dp(28)));

        View grip = new View(this);
        grip.setBackground(Ui.round(0x44FFFFFF, dp(3)));
        sheet.addView(grip, new LinearLayout.LayoutParams(dp(40), dp(5)));

        AvatarView av = new AvatarView(this);
        av.set(c.name, c.lookupKey);
        Data.bindPhoto(this, av, c.photoUri);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(84), dp(84));
        alp.topMargin = dp(16);
        sheet.addView(av, alp);
        TextView name = Ui.text(this, c.name, 22, Color.WHITE, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(10), 0, 0);
        sheet.addView(name);

        String gesture = null;
        for (Stores.Gesture g : gestures.all()) {
            for (Data.Phone p : c.phones) if (Data.sameNumber(p.number, g.number)) gesture = g.prettyPattern();
        }
        TextView meta = Ui.text(this, gesture != null ? "Gesture  " + gesture : (c.starred ? "★ Favourite" : ""), 13, Ui.CYAN, false);
        meta.setGravity(Gravity.CENTER);
        sheet.addView(meta);

        for (Data.Phone p : c.phones) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(8), dp(10));
            row.setBackground(Ui.round(0x22FFFFFF, dp(18)));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = dp(10);
            sheet.addView(row, rlp);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Ui.text(this, p.number, 16, Color.WHITE, true));
            texts.addView(Ui.text(this, p.label.isEmpty() ? "Phone" : p.label, 12, Ui.MUTED, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView msg = iconButton(R.drawable.ic_message, Color.WHITE, Ui.SURFACE2, 42, x -> sms(p.number));
            row.addView(msg);
            ImageView call = iconButton(R.drawable.ic_call, Color.WHITE, Ui.GREEN, 42, x -> {
                dlg.dismiss();
                placeCall(p.number, c.name);
            });
            LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) call.getLayoutParams();
            clp.leftMargin = dp(8);
            row.addView(call);
            row.setOnLongClickListener(x -> {
                copy(p.number);
                return true;
            });
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aclp = new LinearLayout.LayoutParams(-1, -2);
        aclp.topMargin = dp(18);
        sheet.addView(actions, aclp);
        TextView assign = Ui.pill(this, "Assign gesture", Ui.ACCENT, x -> {
            dlg.dismiss();
            choosePhone(c, p -> beginAssign(c, p));
        });
        actions.addView(assign);
        if (c.id >= 0 && c.lookupKey != null) {
            TextView open = Ui.pill(this, "Edit", 0x33FFFFFF, x -> {
                dlg.dismiss();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            ContactsContract.Contacts.getLookupUri(c.id, c.lookupKey)));
                } catch (ActivityNotFoundException e) {
                    toast("No contacts app found");
                }
            });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
            olp.leftMargin = dp(10);
            actions.addView(open, olp);
        }

        dlg.setContentView(sheet);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.getDecorView().setPadding(dp(8), 0, dp(8), dp(8));
        }
        sheet.setTranslationY(dp(300));
        sheet.setAlpha(0f);
        dlg.setOnShowListener(d -> sheet.animate().translationY(0).alpha(1f).setDuration(260).start());
        dlg.show();
    }

    // ===================================================================== actions

    private void placeCall(String num, String label) {
        if (num == null || num.isEmpty()) {
            toast("No number");
            return;
        }
        if (has(Manifest.permission.CALL_PHONE)) {
            startCall(num, Intent.ACTION_CALL);
        } else {
            pendingCallNumber = num;
            pendingCallLabel = label;
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL);
        }
    }

    private void startCall(String num, String action) {
        try {
            startActivity(new Intent(action, Uri.fromParts("tel", num, null)));
        } catch (ActivityNotFoundException | SecurityException e) {
            if (Intent.ACTION_CALL.equals(action)) startCall(num, Intent.ACTION_DIAL);
            else toast("This device can't place calls");
        }
    }

    private void sms(String num) {
        if (num == null || num.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", num, null)));
        } catch (ActivityNotFoundException e) {
            toast("No messaging app found");
        }
    }

    private void addContact(String num) {
        Intent i = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
        if (num != null && !num.isEmpty()) i.putExtra(ContactsContract.Intents.Insert.PHONE, num);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No contacts app found");
        }
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Phone number", text));
            toast("Copied");
        }
    }

    private String detectCountry() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String iso = tm != null ? tm.getNetworkCountryIso() : null;
            if (TextUtils.isEmpty(iso) && tm != null) iso = tm.getSimCountryIso();
            if (!TextUtils.isEmpty(iso)) return iso.toUpperCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
        }
        return Locale.getDefault().getCountry();
    }

    private String format(String raw) {
        if (raw.isEmpty() || raw.contains("*") || raw.contains("#")) return raw;
        String f = PhoneNumberUtils.formatNumber(raw, countryIso);
        return f != null ? f : raw;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
