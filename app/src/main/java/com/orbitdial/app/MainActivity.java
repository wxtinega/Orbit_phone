package com.orbitdial.app;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends BaseActivity implements OrbitDialView.Listener {

    static final String ACTION_CALL_SHORTCUT = "com.orbitdial.app.CALL_FAVOURITE";
    /** Private alias: only Orbit Dial's own shortcuts and notifications can use it to start a call. */
    static final String ALIAS = "com.orbitdial.app.CallFavourite";
    static final String EXTRA_ADD_CALL = "add_call";
    static final String EXTRA_ASSIGN_NUMBER = "assign_number", EXTRA_ASSIGN_NAME = "assign_name";
    static final String EXTRA_TAB = "tab";
    static final int KEYPAD = 0, RECENTS = 1, CONTACTS = 2;

    private int tab = KEYPAD;
    private final StringBuilder number = new StringBuilder();
    private Stores.GestureStore gestures;
    private ToneGenerator tones;
    private String country;
    private boolean addCallMode;
    private String assignNumber, assignName, pickPattern;

    private final View[] pages = new View[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private final View[] navPills = new View[3];
    private TextView missedBadge;

    // keypad
    private TextView numberView, matchView, banner;
    private LinearLayout topArea;
    private OrbitDialView dial;
    private View backspace, messageBtn, callBtn, whatsAppBtn, addContactBtn;

    // recents
    private final RecentsAdapter recents = new RecentsAdapter();
    private ListView recentList;
    private LinearLayout recentEmpty, insights;
    private TextView recentEmptyText, recentEmptyButton, statToday, statTalk, statMissed, statTop;
    private int filter; // 0 all, 1 missed, 2 incoming, 3 outgoing
    private final TextView[] filterChips = new TextView[4];

    // contacts
    private final ContactsAdapter contactsAdapter = new ContactsAdapter();
    private ListView contactList;
    private EditText search;
    private LinearLayout favSection, favRow, contactEmpty;
    private TextView contactCount, contactEmptyText, contactEmptyButton, pickBanner;

    private final ContentObserver observer = new ContentObserver(Data.MAIN) {
        @Override
        public void onChange(boolean self) {
            Data.MAIN.removeCallbacks(reload);
            Data.MAIN.postDelayed(reload, 400);
        }
    };
    private final Runnable reload = this::reload;

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        if (!Prefs.onboarded(this) && !in.getBooleanExtra("skip_setup", false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        if (in.getBooleanExtra("skip_setup", false)) Prefs.setOnboarded(this);
        gestures = new Stores.GestureStore(this);
        country = Actions.country(this);
        setContentView(build());
        if (state != null) {
            number.append(state.getString("number", ""));
            tab = state.getInt("tab", KEYPAD);
        }
        handleIntent(in);
        showTab(tab, false);
        refreshAll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        watch();
        if (Prefs.dialTones(this) && tones == null) {
            try {
                tones = new ToneGenerator(AudioManager.STREAM_DTMF, 55);
            } catch (RuntimeException e) {
                tones = null;
            }
        }
    }

    /** Watch contacts and call history so lists update by themselves. */
    private void watch() {
        getContentResolver().unregisterContentObserver(observer);
        try {
            if (Actions.has(this, Manifest.permission.READ_CONTACTS))
                getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer);
            if (Actions.has(this, Manifest.permission.READ_CALL_LOG))
                getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer);
        } catch (RuntimeException ignored) {
        }
        reload();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(observer);
        if (tones != null) {
            tones.release();
            tones = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("number", number.toString());
        out.putInt("tab", tab);
    }

    @Override
    public void onBackPressed() {
        if (pickPattern != null) {
            cancelPick();
        } else if (tab != KEYPAD) {
            showTab(KEYPAD, true);
        } else if (number.length() > 0) {
            number.setLength(0);
            refreshKeypad();
        } else {
            super.onBackPressed();
        }
    }

    private void handleIntent(Intent in) {
        if (in == null) return;
        String action = in.getAction();
        boolean fromAlias = in.getComponent() != null && ALIAS.equals(in.getComponent().getClassName());
        if (ACTION_CALL_SHORTCUT.equals(action) && fromAlias) {
            String n = in.getStringExtra("number");
            if (n != null) Actions.call(this, n);
            return;
        }
        Uri data = in.getData();
        if (data != null && "tel".equalsIgnoreCase(data.getScheme())) {
            number.setLength(0);
            number.append(data.getSchemeSpecificPart().replaceAll("[^0-9+*#,;]", ""));
            tab = KEYPAD;
        } else if (Intent.ACTION_VIEW.equals(action) && in.getType() != null && in.getType().contains("calls")) {
            tab = RECENTS;
        }
        if (in.hasExtra(EXTRA_TAB)) tab = in.getIntExtra(EXTRA_TAB, KEYPAD);
        addCallMode = in.getBooleanExtra(EXTRA_ADD_CALL, false);
        if (addCallMode) {
            tab = KEYPAD;
            number.setLength(0);
        }
        if (in.hasExtra(EXTRA_ASSIGN_NUMBER)) {
            assignNumber = in.getStringExtra(EXTRA_ASSIGN_NUMBER);
            assignName = in.getStringExtra(EXTRA_ASSIGN_NAME);
            tab = KEYPAD;
        }
        if (pages[0] != null) {
            showTab(tab, false);
            refreshKeypad();
        }
    }

    private void reload() {
        boolean contactsOk = Actions.has(this, Manifest.permission.READ_CONTACTS);
        boolean callsOk = Actions.has(this, Manifest.permission.READ_CALL_LOG);
        if (!contactsOk && !callsOk) {
            refreshAll();
            return;
        }
        Data.IO.execute(() -> {
            Data.loadAll(getApplicationContext());
            Actions.updateShortcuts(getApplicationContext());
            runOnUiThread(() -> {
                if (!isDestroyed()) refreshAll();
            });
        });
    }

    private void refreshAll() {
        refreshKeypad();
        refreshRecents();
        refreshContacts();
    }

    // ===================================================================== shell

    private View build() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout pager = new FrameLayout(this);
        col.addView(pager, new LinearLayout.LayoutParams(-1, 0, 1f));
        pages[KEYPAD] = buildKeypad();
        pages[RECENTS] = buildRecents();
        pages[CONTACTS] = buildContacts();
        for (View p : pages) {
            p.setVisibility(View.GONE);
            pager.addView(p, new FrameLayout.LayoutParams(-1, -1));
        }
        col.addView(buildNav());
        Ui.applyWindow(this, col);
        return root;
    }

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setBackground(Ui.glassCard(this, 28, true));
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(14), dp(6), dp(14), dp(10));
        nav.setLayoutParams(lp);
        String[] labels = {"Keypad", "Recents", "Contacts"};
        int[] icons = {R.drawable.ic_dialpad, R.drawable.ic_history, R.drawable.ic_person};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(dp(56));
            item.setContentDescription(labels[i]);
            FrameLayout iconBox = new FrameLayout(this);
            View pill = new View(this);
            pill.setBackground(Ui.round(Ui.alpha(Ui.accent, Ui.dark ? 0x38 : 0x24), dp(16)));
            iconBox.addView(pill, new FrameLayout.LayoutParams(dp(60), dp(32), Gravity.CENTER));
            ImageView ic = Ui.icon(this, icons[i], Ui.muted);
            iconBox.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            if (i == RECENTS) {
                missedBadge = Ui.text(this, "", 11, 0xFFFFFFFF, true);
                missedBadge.setGravity(Gravity.CENTER);
                missedBadge.setBackground(Ui.round(Ui.danger, dp(9)));
                missedBadge.setMinWidth(dp(18));
                missedBadge.setPadding(dp(5), dp(2), dp(5), dp(2));
                missedBadge.setVisibility(View.GONE);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                blp.rightMargin = dp(8);
                iconBox.addView(missedBadge, blp);
            }
            item.addView(iconBox, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView label = Ui.text(this, labels[i], 12, Ui.muted, true);
            label.setPadding(0, dp(4), 0, 0);
            Ui.oneLine(label);
            item.addView(label);
            item.setOnClickListener(Ui.tap(v -> showTab(idx, true)));
            Ui.press(item);
            navIcons[i] = ic;
            navLabels[i] = label;
            navPills[i] = pill;
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return nav;
    }

    private void showTab(int t, boolean animate) {
        View in = pages[t], out = pages[tab];
        if (animate && t != tab) {
            in.setAlpha(0f);
            in.setTranslationY(dp(10));
            in.setVisibility(View.VISIBLE);
            in.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            out.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                if (pages[tab] != out) out.setVisibility(View.GONE);
                out.setAlpha(1f);
            }).start();
        } else {
            for (View p : pages) p.setVisibility(p == in ? View.VISIBLE : View.GONE);
        }
        tab = t;
        for (int i = 0; i < 3; i++) {
            boolean on = i == t;
            navIcons[i].setImageTintList(ColorStateList.valueOf(on ? Ui.accent : Ui.muted));
            navLabels[i].setTextColor(on ? Ui.text : Ui.muted);
            navPills[i].animate().alpha(on ? 1f : 0f).scaleX(on ? 1f : 0.6f).setDuration(200).start();
        }
        if (t != CONTACTS) hideKeyboard();
        if (t == RECENTS) {
            Prefs.sp(this).edit().putLong("recents_seen", System.currentTimeMillis()).apply();
            try {
                getSystemService(android.telecom.TelecomManager.class).cancelMissedCallsNotification();
            } catch (RuntimeException ignored) {
            }
            getSystemService(android.app.NotificationManager.class).cancel(CallNotifier.ID_MISSED);
            updateBadge();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && search != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private LinearLayout page() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(8), dp(16), 0);
        return p;
    }

    private LinearLayout header(String titleText, View... right) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(4), 0, 0, 0);
        row.addView(Ui.title(this, titleText), new LinearLayout.LayoutParams(0, -2, 1f));
        for (View v : right) row.addView(v);
        return row;
    }

    private LinearLayout emptyState(String button, Runnable onButton, TextView[] refs) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(24), dp(32), dp(48));
        TextView t = Ui.text(this, "", 15, Ui.muted, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView b = Ui.button(this, button, Ui.FILLED, v -> onButton.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(16);
        box.addView(b, lp);
        refs[0] = t;
        refs[1] = b;
        return box;
    }

    private ListView list() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setSelector(new android.graphics.drawable.ColorDrawable(0));
        l.setCacheColorHint(0);
        l.setVerticalScrollBarEnabled(false);
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        l.setClipToPadding(false);
        l.setPadding(0, 0, 0, dp(12));
        return l;
    }

    // ==================================================================== keypad

    private View buildKeypad() {
        LinearLayout p = page();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout searchPill = new LinearLayout(this);
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        searchPill.setMinimumHeight(dp(48));
        searchPill.setPadding(dp(16), 0, dp(16), 0);
        searchPill.setBackground(Ui.ripple(Ui.glassCard(this, 24, false), Ui.round(0xFFFFFFFF, dp(24))));
        searchPill.addView(Ui.icon(this, R.drawable.ic_search, Ui.muted), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView hint = Ui.oneLine(Ui.text(this, "Search contacts", 15, Ui.muted, false));
        hint.setPadding(dp(12), 0, 0, 0);
        searchPill.addView(hint);
        searchPill.setOnClickListener(Ui.tap(v -> {
            showTab(CONTACTS, true);
            search.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }));
        Ui.press(searchPill);
        top.addView(searchPill, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView settings = Ui.iconButton(this, R.drawable.ic_settings, 48, 0, Ui.text, "Settings",
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) settings.getLayoutParams();
        slp.leftMargin = dp(8);
        top.addView(settings);
        p.addView(top);

        banner = Ui.text(this, "", 14, Ui.text, true);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setMinHeight(dp(48));
        banner.setPadding(dp(16), dp(10), dp(16), dp(10));
        banner.setBackground(Ui.rippleRound(this, Ui.alpha(Ui.accent, Ui.dark ? 0x33 : 0x22), 16));
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(10);
        p.addView(banner, blp);

        topArea = new LinearLayout(this);
        topArea.setOrientation(LinearLayout.VERTICAL);
        topArea.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(116));
        tlp.topMargin = dp(10);
        p.addView(topArea, tlp);

        LinearLayout numberRow = new LinearLayout(this);
        numberRow.setGravity(Gravity.CENTER_VERTICAL);
        addContactBtn = Ui.iconButton(this, R.drawable.ic_person_add, 48, 0, Ui.muted, "Add to contacts",
                v -> Actions.addContact(this, number.toString()));
        numberRow.addView(addContactBtn);
        numberView = Ui.text(this, "", 36, Ui.text, false);
        numberView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        numberView.setGravity(Gravity.CENTER);
        numberView.setSingleLine(true);
        numberView.setAutoSizeTextTypeUniformWithConfiguration(18, 36, 1, TypedValue.COMPLEX_UNIT_SP);
        numberView.setHint("Enter a number");
        numberView.setHintTextColor(Ui.alpha(Ui.muted, 0x99));
        numberView.setOnLongClickListener(v -> {
            Ui.actions(this, null,
                    new Ui.Action(R.drawable.ic_copy, "Copy", false, () -> Actions.copy(this, number.toString())),
                    new Ui.Action(R.drawable.ic_reply, "Paste", false, this::paste));
            return true;
        });
        numberRow.addView(numberView, new LinearLayout.LayoutParams(0, dp(52), 1f));
        backspace = Ui.iconButton(this, R.drawable.ic_backspace, 48, 0, Ui.text, "Delete", v -> {
            if (number.length() > 0) {
                number.deleteCharAt(number.length() - 1);
                refreshKeypad();
            }
        });
        backspace.setOnLongClickListener(v -> {
            number.setLength(0);
            Ui.tick(v);
            refreshKeypad();
            return true;
        });
        numberRow.addView(backspace);
        p.addView(numberRow, new LinearLayout.LayoutParams(-1, -2));

        matchView = Ui.oneLine(Ui.text(this, "", 14, Ui.accent, true));
        matchView.setGravity(Gravity.CENTER);
        matchView.setMinHeight(dp(20));
        p.addView(matchView, new LinearLayout.LayoutParams(-1, -2));

        dial = new OrbitDialView(this);
        dial.setStyle(Prefs.DIAL_GRID.equals(Prefs.dial(this)) ? OrbitDialView.GRID : OrbitDialView.ORBIT);
        dial.setListener(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        dlp.topMargin = dp(4);
        p.addView(dial, dlp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        messageBtn = Ui.iconButton(this, R.drawable.ic_message, 56, Ui.glass, Ui.text, "Send a message",
                v -> Actions.sms(this, number.toString()));
        callBtn = Ui.iconButton(this, R.drawable.ic_call, 68, Ui.accent, Ui.onAccent, "Call", v -> onCallPressed());
        whatsAppBtn = Ui.iconButton(this, R.drawable.ic_chat, 56, Ui.glass, Ui.text, "Open in WhatsApp",
                v -> Actions.whatsAppChat(this, number.toString()));
        bottom.addView(messageBtn);
        LinearLayout.LayoutParams mid = (LinearLayout.LayoutParams) callBtn.getLayoutParams();
        mid.leftMargin = dp(32);
        mid.rightMargin = dp(32);
        bottom.addView(callBtn);
        bottom.addView(whatsAppBtn);
        if (dial.hasCallButton()) callBtn.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(72));
        bl.bottomMargin = dp(2);
        p.addView(bottom, bl);
        return p;
    }

    private void paste() {
        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            snack("There's nothing to paste.");
            return;
        }
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        String n = t == null ? "" : t.toString().replaceAll("[^0-9+*#]", "");
        if (n.isEmpty()) {
            snack("The copied text doesn't contain a phone number.");
            return;
        }
        number.setLength(0);
        number.append(n);
        refreshKeypad();
    }

    private String format(String raw) {
        if (raw.isEmpty() || raw.contains("*") || raw.contains("#")) return raw;
        String f = PhoneNumberUtils.formatNumber(raw, country);
        return f != null ? f : raw;
    }

    private void refreshKeypad() {
        if (numberView == null) return;
        String n = number.toString();
        numberView.setText(format(n));
        Data.Contact exact = n.length() >= 3 ? Data.find(n) : null;
        matchView.setText(exact != null ? exact.name : "");
        dial.setReady(!n.isEmpty());
        boolean hasNumber = !n.isEmpty();
        boolean dialable = hasNumber && !n.contains("*") && !n.contains("#") && n.replace("+", "").length() >= 3;
        backspace.setVisibility(hasNumber ? View.VISIBLE : View.INVISIBLE);
        messageBtn.setVisibility(dialable ? View.VISIBLE : View.INVISIBLE);
        whatsAppBtn.setVisibility(dialable && Phonebook.whatsAppPackage(this) != null ? View.VISIBLE : View.INVISIBLE);
        addContactBtn.setVisibility(dialable && exact == null ? View.VISIBLE : View.INVISIBLE);

        // Banner for special modes.
        if (assignNumber != null) {
            banner.setText("Draw a path across 3 or more keys for " + (assignName != null ? assignName : assignNumber) + ".  Tap to cancel.");
            banner.setOnClickListener(Ui.tap(v -> {
                assignNumber = null;
                refreshKeypad();
            }));
            banner.setVisibility(View.VISIBLE);
        } else if (addCallMode) {
            banner.setText("Adding someone to your call. Tap to go back to the call.");
            banner.setOnClickListener(Ui.tap(v -> {
                addCallMode = false;
                startActivity(new Intent(this, InCallActivity.class));
                refreshKeypad();
            }));
            banner.setVisibility(View.VISIBLE);
        } else if (CallManager.hasCalls()) {
            banner.setText("Call in progress. Tap to return to it.");
            banner.setOnClickListener(Ui.tap(v -> startActivity(new Intent(this, InCallActivity.class))));
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }

        // Favourites when idle, matching contacts while typing.
        topArea.removeAllViews();
        if (!hasNumber) {
            List<Data.Contact> favs = new ArrayList<>();
            for (Data.Contact c : Data.contacts) if (c.starred && !c.numbers.isEmpty()) favs.add(c);
            if (!favs.isEmpty()) {
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout row = new LinearLayout(this);
                for (Data.Contact c : favs) row.addView(favourite(c, 56));
                hs.addView(row);
                topArea.addView(hs);
            } else {
                int count = gestures.all().size();
                TextView t = Ui.text(this, count > 0
                        ? count + (count == 1 ? " gesture ready. " : " gestures ready. ") + "Draw a path across the keys to call."
                        : "Tip: drag your finger across 3 or more keys to create a Gesture Speed Dial.", 13, Ui.muted, false);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(16), 0, dp(16), 0);
                topArea.addView(t);
            }
        } else if (exact == null) {
            for (Data.Match m : Data.search(n, true, 2)) topArea.addView(suggestion(m, n));
        }

    }

    private View favourite(Data.Contact c, int size) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        AvatarView av = new AvatarView(this);
        av.set(c.name, c.lookupKey);
        Data.bindPhoto(this, av, c.thumbUri);
        item.addView(av, new LinearLayout.LayoutParams(dp(size), dp(size)));
        TextView n = Ui.oneLine(Ui.text(this, c.name.split("\\s+")[0], 12, Ui.text, false));
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(6), 0, 0);
        item.addView(n, new LinearLayout.LayoutParams(dp(size + 16), -2));
        item.setContentDescription("Call " + c.name);
        item.setOnClickListener(Ui.tap(v -> {
            if (pickPattern != null) onContactPicked(c);
            else Actions.call(this, c.primary());
        }));
        item.setOnLongClickListener(v -> {
            Actions.openContact(this, c.id, c.primary());
            return true;
        });
        item.setBackground(Ui.rippleRound(this, 0, 16));
        Ui.press(item);
        return item;
    }

    private View suggestion(Data.Match m, String typed) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(10), dp(2), dp(2), dp(2));
        row.setBackground(Ui.ripple(Ui.glassCard(this, 18, false), Ui.round(0xFFFFFFFF, dp(18))));
        AvatarView av = new AvatarView(this);
        av.set(m.contact.name, m.contact.lookupKey);
        Data.bindPhoto(this, av, m.contact.thumbUri);
        row.addView(av, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        texts.addView(Ui.oneLine(Ui.text(this, m.contact.name, 15, Ui.text, true)));
        TextView sub = Ui.oneLine(Ui.text(this, highlight(m.number.number, typed), 13, Ui.muted, false));
        sub.setPadding(0, dp(3), 0, 0);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(Ui.iconButton(this, R.drawable.ic_call, 48, 0, Ui.accent, "Call " + m.contact.name,
                v -> Actions.call(this, m.number.number)));
        row.setOnClickListener(Ui.tap(v -> {
            number.setLength(0);
            number.append(m.number.number.replaceAll("[^0-9+*#]", ""));
            refreshKeypad();
        }));
        Ui.press(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    /** Colours the typed digits inside a number. */
    private CharSequence highlight(String full, String typed) {
        String d = Data.digits(typed);
        SpannableString s = new SpannableString(full);
        if (d.length() < 2) return s;
        StringBuilder only = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        for (int i = 0; i < full.length(); i++) {
            if (Character.isDigit(full.charAt(i))) {
                only.append(full.charAt(i));
                map.add(i);
            }
        }
        int at = only.indexOf(d);
        if (at >= 0) {
            s.setSpan(new ForegroundColorSpan(Ui.accent), map.get(at), map.get(at + d.length() - 1) + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    @Override
    public void onDigit(char key) {
        if (number.length() >= 40) return;
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
            Actions.voicemail(this);
        }
    }

    @Override
    public void onCallPressed() {
        if (number.length() == 0) {
            for (Data.Call c : Data.calls) {
                if (c.outgoing() && !c.number.isEmpty()) {
                    number.append(c.number.replaceAll("[^0-9+*#]", ""));
                    refreshKeypad();
                    snack("Last number dialled. Tap call again.");
                    return;
                }
            }
            snack("Type a number first.");
            return;
        }
        String n = number.toString();
        if (addCallMode) {
            addCallMode = false;
            number.setLength(0);
            refreshKeypad();
        }
        Actions.call(this, n);
    }

    @Override
    public void onGesture(String pattern) {
        if (assignNumber != null) {
            gestures.put(pattern, assignName != null ? assignName : format(assignNumber), assignNumber);
            snack("Saved. Drawing " + Stores.prettify(pattern) + " now calls " + (assignName != null ? assignName : assignNumber) + ".");
            assignNumber = null;
            assignName = null;
            refreshKeypad();
            return;
        }
        Stores.Gesture g = gestures.get(pattern);
        if (g != null) {
            snack("Calling " + g.name);
            Actions.call(this, g.number);
            return;
        }
        String typed = number.toString();
        List<Ui.Action> acts = new ArrayList<>();
        acts.add(new Ui.Action(R.drawable.ic_person, "Choose a contact", false, () -> startPick(pattern)));
        if (typed.replace("+", "").length() >= 3) {
            acts.add(new Ui.Action(R.drawable.ic_call, "Use " + format(typed), false, () -> {
                Data.Contact c = Data.find(typed);
                gestures.put(pattern, c != null ? c.name : format(typed), typed);
                snack("Saved gesture " + Stores.prettify(pattern) + ".");
                refreshKeypad();
            }));
        }
        Ui.actions(this, "New gesture  " + Stores.prettify(pattern), acts.toArray(new Ui.Action[0]));
    }

    private void playTone(char key) {
        if (tones == null) return;
        try {
            if (Settings.System.getInt(getContentResolver(), Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 0) return;
        } catch (RuntimeException ignored) {
        }
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
        tones.startTone(tone, 100);
    }

    private void startPick(String pattern) {
        pickPattern = pattern;
        pickBanner.setText("Tap the person " + Stores.prettify(pattern) + " should call.  Tap here to cancel.");
        pickBanner.setVisibility(View.VISIBLE);
        showTab(CONTACTS, true);
    }

    private void cancelPick() {
        pickPattern = null;
        pickBanner.setVisibility(View.GONE);
    }

    private void onContactPicked(Data.Contact c) {
        String pattern = pickPattern;
        cancelPick();
        if (c.numbers.size() == 1) {
            savePicked(pattern, c, c.numbers.get(0));
            return;
        }
        List<Ui.Action> acts = new ArrayList<>();
        for (Data.Number n : c.numbers) {
            acts.add(new Ui.Action(R.drawable.ic_call, n.number + (n.label.isEmpty() ? "" : "  " + n.label), false,
                    () -> savePicked(pattern, c, n)));
        }
        Ui.actions(this, c.name, acts.toArray(new Ui.Action[0]));
    }

    private void savePicked(String pattern, Data.Contact c, Data.Number n) {
        gestures.put(pattern, c.name, n.number);
        showTab(KEYPAD, true);
        refreshKeypad();
        snack("Saved. Drawing " + Stores.prettify(pattern) + " now calls " + c.name + ".");
    }

    // =================================================================== recents

    private View buildRecents() {
        LinearLayout p = page();
        p.addView(header("Recents"));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        String[] names = {"All", "Missed", "Incoming", "Outgoing"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView c = Ui.text(this, names[i], 14, Ui.text, true);
            c.setGravity(Gravity.CENTER);
            c.setMinHeight(dp(40));
            c.setPadding(dp(16), 0, dp(16), 0);
            c.setOnClickListener(Ui.tap(v -> {
                filter = idx;
                refreshRecents();
                recentList.setSelection(0);
            }));
            Ui.press(c);
            filterChips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            lp.rightMargin = dp(8);
            row.addView(c, lp);
        }
        hs.addView(row);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.bottomMargin = dp(6);
        p.addView(hs, hlp);

        FrameLayout frame = new FrameLayout(this);
        p.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        recentList = list();
        insights = buildInsights();
        recentList.addHeaderView(insights, null, false);
        recentList.setAdapter(recents);
        frame.addView(recentList, new FrameLayout.LayoutParams(-1, -1));
        TextView[] refs = new TextView[2];
        recentEmpty = emptyState("Allow access", () -> requestOrSettings(Manifest.permission.READ_CALL_LOG), refs);
        recentEmptyText = refs[0];
        recentEmptyButton = refs[1];
        frame.addView(recentEmpty, new FrameLayout.LayoutParams(-1, -1));
        return p;
    }

    private LinearLayout buildInsights() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.glassCard(this, 20, false));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout stats = new LinearLayout(this);
        statToday = stat(stats, "Calls today");
        statTalk = stat(stats, "Talk time");
        statMissed = stat(stats, "Missed");
        card.addView(stats);
        statTop = Ui.text(this, "", 13, Ui.muted, false);
        statTop.setPadding(0, dp(10), 0, 0);
        card.addView(statTop);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(2), 0, dp(6));
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private TextView stat(LinearLayout parent, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView v = Ui.oneLine(Ui.text(this, "0", 22, Ui.text, true));
        col.addView(v);
        TextView l = Ui.oneLine(Ui.text(this, label, 12, Ui.muted, false));
        l.setPadding(0, dp(4), 0, 0);
        col.addView(l);
        parent.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return v;
    }

    private void requestOrSettings(String perm) {
        String key = "asked_" + perm;
        if (!Prefs.sp(this).getBoolean(key, false) || shouldShowRequestPermissionRationale(perm)) {
            Prefs.sp(this).edit().putBoolean(key, true).apply();
            requestPermissions(new String[]{perm}, 9001);
        } else {
            Actions.openAppSettings(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 9001) watch();
    }

    /** A list entry: either a day heading or a group of consecutive calls with one number. */
    private static final class Entry {
        final String day;
        final Data.Call call;
        final List<Long> ids = new ArrayList<>();
        int count = 1;

        Entry(String day, Data.Call call) {
            this.day = day;
            this.call = call;
        }
    }

    private void refreshRecents() {
        if (recentList == null) return;
        boolean allowed = Actions.has(this, Manifest.permission.READ_CALL_LOG);
        for (int i = 0; i < 4; i++) {
            boolean on = i == filter;
            filterChips[i].setBackground(Ui.rippleRound(this, on ? Ui.accent : Ui.glassStrong, 20));
            filterChips[i].setTextColor(on ? Ui.onAccent : Ui.text);
        }
        List<Entry> out = new ArrayList<>();
        String lastDay = null;
        Entry last = null;
        for (Data.Call c : Data.calls) {
            if (filter == 1 && !c.missed()) continue;
            if (filter == 2 && (c.missed() || c.outgoing())) continue;
            if (filter == 3 && !c.outgoing()) continue;
            String day = Ui.dayLabel(c.date);
            if (!day.equals(lastDay)) {
                out.add(new Entry(day, null));
                lastDay = day;
                last = null;
            }
            if (last != null && Data.sameNumber(last.call.number, c.number) && last.call.missed() == c.missed()
                    && last.call.outgoing() == c.outgoing()) {
                last.count++;
                last.ids.add(c.id);
            } else {
                last = new Entry(null, c);
                last.ids.add(c.id);
                out.add(last);
            }
        }
        recents.items = out;
        recents.notesCache.clear();
        recents.notifyDataSetChanged();
        insights.setVisibility(filter == 0 && !Data.calls.isEmpty() ? View.VISIBLE : View.GONE);
        insights.getChildAt(0).setVisibility(filter == 0 && !Data.calls.isEmpty() ? View.VISIBLE : View.GONE);
        if (!allowed) {
            recentEmptyText.setText("Orbit Dial needs access to your call history to show it here.");
            recentEmptyButton.setVisibility(View.VISIBLE);
        } else {
            recentEmptyText.setText(filter == 1 ? "No missed calls." : "No calls yet.");
            recentEmptyButton.setVisibility(View.GONE);
        }
        recentEmpty.setVisibility(out.isEmpty() && (Data.loaded || !allowed) ? View.VISIBLE : View.GONE);
        updateStats();
        updateBadge();
    }

    private void updateStats() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long dayStart = cal.getTimeInMillis(), weekStart = dayStart - 6L * 86400000L;
        int today = 0, missed = 0;
        long talk = 0;
        Map<String, Integer> freq = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Data.Call c : Data.calls) {
            if (c.date >= dayStart) {
                today++;
                talk += c.duration;
                if (c.missed()) missed++;
            }
            if (c.date >= weekStart && !c.missed() && !c.number.isEmpty()) {
                String k = Data.key(c.number);
                freq.put(k, freq.getOrDefault(k, 0) + 1);
                String nm = Data.nameFor(c.number);
                names.put(k, nm != null ? nm : c.cachedName != null && !c.cachedName.isEmpty() ? c.cachedName : c.number);
            }
        }
        statToday.setText(String.valueOf(today));
        long m = talk / 60;
        statTalk.setText(talk == 0 ? "0 min" : m < 60 ? Math.max(1, m) + " min" : (m / 60) + " hr " + (m % 60) + " min");
        statMissed.setText(String.valueOf(missed));
        statMissed.setTextColor(missed > 0 ? Ui.danger : Ui.text);
        String top = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
        statTop.setText(top == null ? "Your call activity for the week shows here."
                : "Most talked to this week: " + names.get(top) + ", " + best + (best == 1 ? " call" : " calls"));
    }

    private void updateBadge() {
        if (missedBadge == null) return;
        long seen = Prefs.sp(this).getLong("recents_seen", 0);
        int n = 0;
        for (Data.Call c : Data.calls) {
            if (c.date <= seen) break;
            if (c.missed()) n++;
        }
        missedBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        missedBadge.setVisibility(n > 0 && tab != RECENTS ? View.VISIBLE : View.GONE);
    }

    private final class RecentsAdapter extends BaseAdapter {
        List<Entry> items = new ArrayList<>();
        final Map<String, List<Notes.Item>> notesCache = new HashMap<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return items.get(i).day != null ? 0 : 1; }
        @Override public boolean isEnabled(int i) { return items.get(i).day == null; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Entry e = items.get(pos);
            if (e.day != null) {
                TextView t = convert instanceof TextView ? (TextView) convert : Ui.section(MainActivity.this, "");
                t.setText(e.day);
                return t;
            }
            Row r = convert != null && convert.getTag() instanceof Row ? (Row) convert.getTag() : new Row();
            Data.Call c = e.call;
            Data.Contact ct = Data.find(c.number);
            String title = ct != null ? ct.name : !TextUtils.isEmpty(c.cachedName) ? c.cachedName
                    : c.number.isEmpty() ? "Private number" : format(c.number);
            r.avatar.set(ct != null ? ct.name : null, ct != null ? ct.lookupKey : c.number);
            Data.bindPhoto(MainActivity.this, r.avatar, ct != null ? ct.thumbUri : null);
            r.title.setText(e.count > 1 ? title + "  (" + e.count + ")" : title);
            r.title.setTextColor(c.missed() ? Ui.danger : Ui.text);
            int icon = c.missed() ? R.drawable.ic_call_missed : c.outgoing() ? R.drawable.ic_call_out : R.drawable.ic_call_in;
            r.typeIcon.setImageResource(icon);
            r.typeIcon.setImageTintList(ColorStateList.valueOf(c.missed() ? Ui.danger : Ui.muted));
            r.typeIcon.setVisibility(View.VISIBLE);
            StringBuilder sub = new StringBuilder();
            String label = ct != null ? labelFor(ct, c.number) : c.numberLabel;
            if (!TextUtils.isEmpty(label)) sub.append(label).append("  ·  ");
            sub.append(Ui.ago(c.date));
            String d = Ui.duration(c.duration);
            if (!d.isEmpty()) sub.append("  ·  ").append(d);
            r.sub.setText(sub);
            List<Notes.Item> notes = notesCache.get(c.number);
            if (notes == null) {
                notes = Notes.forNumber(MainActivity.this, c.number);
                notesCache.put(c.number, notes);
            }
            Notes.Item note = Notes.forCall(notes, c.date, Notes.NOTE);
            Notes.Item rec = Notes.forCall(notes, c.date, Notes.RECORDING);
            if (note != null || rec != null) {
                r.extra.setVisibility(View.VISIBLE);
                r.extraText.setText(note != null ? note.text : "Recording, " + Ui.duration(rec.length));
                r.extraIcon.setImageResource(note != null ? R.drawable.ic_notes : R.drawable.ic_record);
                r.extraIcon.setImageTintList(ColorStateList.valueOf(note != null ? Ui.accent : Ui.danger));
            } else {
                r.extra.setVisibility(View.GONE);
            }
            r.action.setImageResource(R.drawable.ic_call);
            r.action.setVisibility(c.number.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            r.action.setContentDescription("Call " + title);
            r.action.setOnClickListener(Ui.tap(v -> Actions.call(MainActivity.this, c.number)));
            r.line.setOnClickListener(Ui.tap(v -> {
                if (c.number.isEmpty()) snack("This caller hid their number.");
                else Actions.openContact(MainActivity.this, ct != null ? ct.id : -1, c.number);
            }));
            r.line.setOnLongClickListener(v -> {
                recentActions(e, ct, title);
                return true;
            });
            return r.root;
        }
    }

    private String labelFor(Data.Contact c, String number) {
        for (Data.Number n : c.numbers) if (Data.sameNumber(n.number, number)) return n.label;
        return "";
    }

    private void recentActions(Entry e, Data.Contact ct, String title) {
        Data.Call c = e.call;
        if (c.number.isEmpty()) return;
        boolean blocked = Phonebook.isBlocked(this, c.number);
        Ui.actions(this, title,
                new Ui.Action(R.drawable.ic_call, "Call", false, () -> Actions.call(this, c.number)),
                new Ui.Action(R.drawable.ic_message, "Send message", false, () -> Actions.sms(this, c.number)),
                Phonebook.whatsAppPackage(this) != null
                        ? new Ui.Action(R.drawable.ic_chat, "WhatsApp", false, () -> Actions.whatsAppChat(this, c.number)) : null,
                new Ui.Action(R.drawable.ic_dialpad, "Edit number before calling", false, () -> {
                    number.setLength(0);
                    number.append(c.number.replaceAll("[^0-9+*#]", ""));
                    showTab(KEYPAD, true);
                    refreshKeypad();
                }),
                new Ui.Action(R.drawable.ic_copy, "Copy number", false, () -> Actions.copy(this, c.number)),
                ct == null ? new Ui.Action(R.drawable.ic_person_add, "Add to contacts", false, () -> Actions.addContact(this, c.number)) : null,
                new Ui.Action(R.drawable.ic_block, blocked ? "Unblock number" : "Block number", !blocked, () -> toggleBlock(c.number, blocked)),
                new Ui.Action(R.drawable.ic_delete, e.count > 1 ? "Delete these " + e.count + " calls" : "Delete from history", true, () -> {
                    try {
                        Phonebook.deleteCalls(this, e.ids);
                        reload();
                    } catch (Phonebook.Problem p) {
                        Ui.snack(this, p.getMessage(), "Allow", () -> requestPermissions(new String[]{Manifest.permission.WRITE_CALL_LOG}, 9001));
                    }
                }));
    }

    private void toggleBlock(String n, boolean blocked) {
        try {
            if (blocked) Phonebook.unblock(this, n);
            else Phonebook.block(this, n);
            snack(blocked ? "Number unblocked." : "Number blocked. Calls from it won't ring.");
        } catch (Phonebook.Problem p) {
            Ui.snack(this, p.getMessage(), Actions.isDefaultPhoneApp(this) ? null : "Set up", () -> Actions.askToBeDefault(this));
        }
    }

    /** One reusable list row. */
    private final class Row {
        final LinearLayout root, line, extra;
        final AvatarView avatar;
        final TextView header, title, sub, extraText;
        final ImageView typeIcon, action, extraIcon;

        Row() {
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setTag(this);
            root.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            header = Ui.section(MainActivity.this, "");
            header.setVisibility(View.GONE);
            root.addView(header);
            line = new LinearLayout(MainActivity.this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(64));
            line.setPadding(dp(8), dp(8), dp(2), dp(8));
            line.setBackground(Ui.rippleRound(MainActivity.this, 0, 16));
            root.addView(line, new LinearLayout.LayoutParams(-1, -2));
            avatar = new AvatarView(MainActivity.this);
            line.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(14), 0, dp(6), 0);
            title = Ui.oneLine(Ui.text(MainActivity.this, "", 16, Ui.text, true));
            texts.addView(title);
            LinearLayout subRow = new LinearLayout(MainActivity.this);
            subRow.setGravity(Gravity.CENTER_VERTICAL);
            subRow.setPadding(0, dp(4), 0, 0);
            typeIcon = Ui.icon(MainActivity.this, R.drawable.ic_call_in, Ui.muted);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(15), dp(15));
            tlp.rightMargin = dp(5);
            subRow.addView(typeIcon, tlp);
            typeIcon.setVisibility(View.GONE);
            sub = Ui.oneLine(Ui.text(MainActivity.this, "", 13, Ui.muted, false));
            subRow.addView(sub, new LinearLayout.LayoutParams(0, -2, 1f));
            texts.addView(subRow);
            extra = new LinearLayout(MainActivity.this);
            extra.setGravity(Gravity.CENTER_VERTICAL);
            extra.setPadding(0, dp(5), 0, 0);
            extraIcon = Ui.icon(MainActivity.this, R.drawable.ic_notes, Ui.accent);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(14), dp(14));
            elp.rightMargin = dp(6);
            extra.addView(extraIcon, elp);
            extraText = Ui.oneLine(Ui.text(MainActivity.this, "", 13, Ui.text, false));
            extra.addView(extraText, new LinearLayout.LayoutParams(0, -2, 1f));
            extra.setVisibility(View.GONE);
            texts.addView(extra);
            line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            action = Ui.iconButton(MainActivity.this, R.drawable.ic_call, 48, 0, Ui.accent, "Call", null);
            line.addView(action);
            Ui.press(line);
        }
    }

    // ================================================================== contacts

    private View buildContacts() {
        LinearLayout p = page();
        ImageView add = Ui.iconButton(this, R.drawable.ic_person_add, 48, 0, Ui.text, "New contact", v -> Actions.addContact(this, null));
        p.addView(header("Contacts", add));

        pickBanner = Ui.text(this, "", 14, Ui.text, true);
        pickBanner.setMinHeight(dp(48));
        pickBanner.setGravity(Gravity.CENTER_VERTICAL);
        pickBanner.setPadding(dp(16), dp(10), dp(16), dp(10));
        pickBanner.setBackground(Ui.rippleRound(this, Ui.alpha(Ui.accent, Ui.dark ? 0x33 : 0x22), 16));
        pickBanner.setOnClickListener(Ui.tap(v -> cancelPick()));
        pickBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.bottomMargin = dp(10);
        p.addView(pickBanner, plp);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(Ui.glassCard(this, 24, false));
        searchRow.setPadding(dp(16), 0, dp(4), 0);
        searchRow.addView(Ui.icon(this, R.drawable.ic_search, Ui.muted), new LinearLayout.LayoutParams(dp(20), dp(20)));
        search = new EditText(this);
        search.setHint("Search name, number or nickname");
        search.setHintTextColor(Ui.muted);
        search.setTextColor(Ui.text);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(dp(12), 0, dp(4), 0);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1f));
        ImageView clear = Ui.iconButton(this, R.drawable.ic_close, 44, 0, Ui.muted, "Clear search", v -> search.setText(""));
        clear.setVisibility(View.GONE);
        searchRow.addView(clear);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                clear.setVisibility(e.length() > 0 ? View.VISIBLE : View.GONE);
                refreshContacts();
            }
        });
        p.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));

        contactList = list();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        favSection = new LinearLayout(this);
        favSection.setOrientation(LinearLayout.VERTICAL);
        favSection.addView(Ui.section(this, "Favourites"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        favRow = new LinearLayout(this);
        hs.addView(favRow);
        favSection.addView(hs);
        head.addView(favSection);
        contactCount = Ui.section(this, "");
        head.addView(contactCount);
        contactList.addHeaderView(head, null, false);
        contactList.setAdapter(contactsAdapter);
        contactList.setFastScrollEnabled(true);
        contactList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int s) {
                if (s == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(contactList, new FrameLayout.LayoutParams(-1, -1));
        TextView[] refs = new TextView[2];
        contactEmpty = emptyState("Allow access", () -> requestOrSettings(Manifest.permission.READ_CONTACTS), refs);
        contactEmptyText = refs[0];
        contactEmptyButton = refs[1];
        frame.addView(contactEmpty, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, 0, 1f);
        flp.topMargin = dp(4);
        p.addView(frame, flp);
        return p;
    }

    private void refreshContacts() {
        if (contactList == null) return;
        boolean allowed = Actions.has(this, Manifest.permission.READ_CONTACTS);
        String q = search.getText().toString().trim();
        List<Data.Contact> shown = new ArrayList<>();
        if (q.isEmpty()) {
            for (Data.Contact c : Data.contacts) if (!c.numbers.isEmpty()) shown.add(c);
        } else {
            for (Data.Match m : Data.search(q, false, 500)) shown.add(m.contact);
        }
        contactsAdapter.items = shown;
        contactsAdapter.searching = !q.isEmpty();
        contactsAdapter.notifyDataSetChanged();
        contactCount.setText(q.isEmpty() ? (shown.isEmpty() ? "" : shown.size() + " contacts") : shown.size() + " found");
        favRow.removeAllViews();
        for (Data.Contact c : Data.contacts) if (c.starred && !c.numbers.isEmpty()) favRow.addView(favourite(c, 60));
        favSection.setVisibility(q.isEmpty() && favRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (!allowed) {
            contactEmptyText.setText("Orbit Dial needs access to your contacts to show them here.");
            contactEmptyButton.setVisibility(View.VISIBLE);
        } else {
            contactEmptyText.setText(q.isEmpty() ? "No contacts with phone numbers yet." : "No one matches \"" + q + "\".");
            contactEmptyButton.setVisibility(View.GONE);
        }
        contactEmpty.setVisibility(shown.isEmpty() && (Data.loaded || !allowed) ? View.VISIBLE : View.GONE);
    }

    private final class ContactsAdapter extends BaseAdapter {
        List<Data.Contact> items = new ArrayList<>();
        boolean searching;

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Row r = convert != null && convert.getTag() instanceof Row ? (Row) convert.getTag() : new Row();
            Data.Contact c = items.get(pos);
            char sec = c.section();
            boolean head = !searching && (pos == 0 || items.get(pos - 1).section() != sec);
            r.header.setText(String.valueOf(sec));
            r.header.setVisibility(head ? View.VISIBLE : View.GONE);
            r.avatar.set(c.name, c.lookupKey);
            Data.bindPhoto(MainActivity.this, r.avatar, c.thumbUri);
            r.title.setText(c.name);
            r.title.setTextColor(Ui.text);
            r.typeIcon.setVisibility(View.GONE);
            Data.Number n = c.numbers.get(0);
            String sub = (n.label.isEmpty() ? "" : n.label + "  ·  ") + n.number;
            if (c.numbers.size() > 1) sub += "  ·  +" + (c.numbers.size() - 1) + " more";
            r.sub.setText(sub);
            r.extra.setVisibility(View.GONE);
            r.action.setImageResource(R.drawable.ic_call);
            r.action.setVisibility(View.VISIBLE);
            r.action.setContentDescription("Call " + c.name);
            r.action.setOnClickListener(Ui.tap(v -> {
                if (pickPattern != null) onContactPicked(c);
                else Actions.call(MainActivity.this, c.primary());
            }));
            r.line.setOnClickListener(Ui.tap(v -> {
                if (pickPattern != null) onContactPicked(c);
                else Actions.openContact(MainActivity.this, c.id, c.primary());
            }));
            r.line.setOnLongClickListener(v -> {
                Ui.actions(MainActivity.this, c.name,
                        new Ui.Action(R.drawable.ic_call, "Call", false, () -> Actions.call(MainActivity.this, c.primary())),
                        new Ui.Action(R.drawable.ic_message, "Send message", false, () -> Actions.sms(MainActivity.this, c.primary())),
                        Phonebook.whatsAppPackage(MainActivity.this) != null
                                ? new Ui.Action(R.drawable.ic_chat, "WhatsApp", false, () -> Actions.whatsAppChat(MainActivity.this, c.primary())) : null,
                        new Ui.Action(c.starred ? R.drawable.ic_star : R.drawable.ic_star_border,
                                c.starred ? "Remove from favourites" : "Add to favourites", false, () -> {
                            try {
                                Phonebook.setStarred(MainActivity.this, c.id, !c.starred);
                                c.starred = !c.starred;
                                refreshAll();
                            } catch (Phonebook.Problem p) {
                                snack(p.getMessage());
                            }
                        }),
                        new Ui.Action(R.drawable.ic_edit, "Edit contact", false, () -> Actions.editContact(MainActivity.this, c.id, c.lookupKey)));
                return true;
            });
            return r.root;
        }
    }
}
