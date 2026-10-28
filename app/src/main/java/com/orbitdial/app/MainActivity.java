package com.orbitdial.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements OrbitDialView.Listener {

    private static final int REQ_CALL = 42;
    private static final int BG = Color.parseColor("#0B1020");
    private static final int SURFACE = Color.parseColor("#161C33");
    private static final int ACCENT = Color.parseColor("#7C5CFF");
    private static final int CALL_GREEN = Color.parseColor("#22C55E");
    private static final int MUTED = Color.parseColor("#8A93B8");

    private final StringBuilder number = new StringBuilder();
    private TextView numberView, hintView;
    private OrbitDialView dial;
    private ToneGenerator tones;
    private Stores.GestureStore gestures;
    private Stores.RecentStore recents;
    private String countryIso;
    private String pendingNumber, pendingLabel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        gestures = new Stores.GestureStore(this);
        recents = new Stores.RecentStore(this);
        countryIso = detectCountry();
        try {
            tones = new ToneGenerator(AudioManager.STREAM_DTMF, 70);
        } catch (RuntimeException e) {
            tones = null; // some devices refuse; the app works silently
        }

        setContentView(buildUi());

        if (state != null) {
            number.append(state.getString("number", ""));
        } else {
            readDialIntent(getIntent());
        }
        refreshNumber();
        showDefaultHint();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readDialIntent(intent);
        refreshNumber();
    }

    /** Lets other apps hand us tel: links ("Open with Orbit Dial"). */
    private void readDialIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null && "tel".equalsIgnoreCase(data.getScheme())) {
            String n = data.getSchemeSpecificPart();
            if (n != null) {
                number.setLength(0);
                number.append(n.replaceAll("[^0-9+*#]", ""));
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("number", number.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tones != null) tones.release();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("Orbit Dial");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Tap to dial · drag across keys for Gesture Speed Dial");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(subtitle);

        // Number display + backspace.
        LinearLayout numberRow = new LinearLayout(this);
        numberRow.setOrientation(LinearLayout.HORIZONTAL);
        numberRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(28);
        root.addView(numberRow, rowLp);

        View spacer = new View(this);
        numberRow.addView(spacer, new LinearLayout.LayoutParams(dp(48), dp(48)));

        numberView = new TextView(this);
        numberView.setTextColor(Color.WHITE);
        numberView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        numberView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        numberView.setGravity(Gravity.CENTER);
        numberView.setSingleLine(true);
        numberView.setEllipsize(TextUtils.TruncateAt.START);
        numberView.setTextIsSelectable(true);
        numberRow.addView(numberView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView backspace = new TextView(this);
        backspace.setText("⌫");
        backspace.setTextColor(MUTED);
        backspace.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        backspace.setGravity(Gravity.CENTER);
        backspace.setContentDescription("Delete digit. Long-press to clear.");
        backspace.setOnClickListener(v -> {
            if (number.length() > 0) {
                number.deleteCharAt(number.length() - 1);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                refreshNumber();
                showDefaultHint();
            }
        });
        backspace.setOnLongClickListener(v -> {
            number.setLength(0);
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            refreshNumber();
            showDefaultHint();
            return true;
        });
        numberRow.addView(backspace, new LinearLayout.LayoutParams(dp(48), dp(48)));

        hintView = new TextView(this);
        hintView.setTextColor(MUTED);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hintView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        root.addView(hintView, hintLp);

        // The circular dial pad.
        dial = new OrbitDialView(this);
        dial.setColors(ACCENT, CALL_GREEN);
        dial.setListener(this);
        LinearLayout.LayoutParams dialLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        dialLp.topMargin = dp(12);
        dialLp.bottomMargin = dp(12);
        root.addView(dial, dialLp);

        // Bottom actions.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(pill("Gestures", v -> showGestureManager()), pillLp(0));
        actions.addView(pill("Recents", v -> showRecents()), pillLp(dp(12)));
        return root;
    }

    private TextView pill(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(dp(24));
        t.setBackground(bg);
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout.LayoutParams pillLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = leftMargin;
        return lp;
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
        if (raw.isEmpty() || raw.contains("*") || raw.contains("#")) return raw; // keep USSD codes as typed
        String f = PhoneNumberUtils.formatNumber(raw, countryIso);
        return f != null ? f : raw;
    }

    private void refreshNumber() {
        numberView.setText(format(number.toString()));
    }

    private void showDefaultHint() {
        int count = gestures.all().size();
        hintView.setText(count == 0
                ? "Tip: drag across 3+ keys to create a gesture"
                : count + (count == 1 ? " gesture" : " gestures") + " ready · long-press 0 for +");
        hintView.setTextColor(MUTED);
    }

    private void hint(String text, int color) {
        hintView.setText(text);
        hintView.setTextColor(color);
    }

    // ---------------------------------------------------------- dial pad events

    @Override
    public void onDigit(char key) {
        if (number.length() >= 30) return;
        number.append(key);
        playTone(key);
        refreshNumber();
        showDefaultHint();
    }

    @Override
    public void onDigitLongPress(char key) {
        if (key == '0') {
            number.append('+');
            refreshNumber();
        } else if (key == '1' && number.length() == 0) {
            hint("Tip: drag 1 → 5 → 9 (or any path) to make a speed dial", ACCENT);
        }
    }

    @Override
    public void onCallPressed() {
        if (number.length() == 0) {
            // Like most dialers: an empty call press recalls the last number.
            List<Stores.Recent> list = recents.all();
            if (!list.isEmpty()) {
                number.append(list.get(0).number);
                refreshNumber();
                hint("Last number restored · tap CALL again", ACCENT);
            } else {
                hint("Type a number first", MUTED);
            }
            return;
        }
        placeCall(number.toString(), null);
    }

    @Override
    public void onGesture(String pattern) {
        Stores.Gesture g = gestures.get(pattern);
        if (g != null) {
            number.setLength(0);
            number.append(g.number);
            refreshNumber();
            hint("Gesture " + g.prettyPattern() + "  →  " + g.name, CALL_GREEN);
            placeCall(g.number, g.name);
        } else {
            showBindDialog(pattern);
        }
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
        tones.startTone(tone, 120);
    }

    // ------------------------------------------------------- Gesture Speed Dial

    private void showBindDialog(String pattern) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        EditText name = new EditText(this);
        name.setHint("Name (e.g. Mum)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(name);

        EditText num = new EditText(this);
        num.setHint("Phone number");
        num.setInputType(InputType.TYPE_CLASS_PHONE);
        num.setText(number.toString());
        box.addView(num);

        new AlertDialog.Builder(this)
                .setTitle("New gesture: " + Stores.prettify(pattern))
                .setMessage("Draw this path on the dial any time to call this person instantly.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = num.getText().toString().replaceAll("[^0-9+*#]", "");
                    if (n.isEmpty()) {
                        toast("Gesture not saved: number was empty");
                        return;
                    }
                    String label = name.getText().toString().trim();
                    if (label.isEmpty()) label = format(n);
                    gestures.put(pattern, label, n);
                    toast("Saved: " + Stores.prettify(pattern) + " calls " + label);
                    showDefaultHint();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showGestureManager() {
        List<Stores.Gesture> list = gestures.all();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Gesture Speed Dial")
                    .setMessage("No gestures yet.\n\nDrag your finger across three or more keys on the dial "
                            + "(for example 1 → 5 → 9). When you let go, you can link that path to a contact. "
                            + "Next time, just draw the path to call them.")
                    .setPositiveButton("Got it", null)
                    .show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Stores.Gesture g = list.get(i);
            items[i] = g.prettyPattern() + "   ·   " + g.name + "  (" + format(g.number) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Your gestures")
                .setItems(items, (d, which) -> {
                    Stores.Gesture g = list.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(g.prettyPattern() + "  →  " + g.name)
                            .setItems(new String[]{"Call now", "Delete gesture"}, (d2, opt) -> {
                                if (opt == 0) {
                                    onGesture(g.pattern);
                                } else {
                                    gestures.remove(g.pattern);
                                    toast("Deleted");
                                    showDefaultHint();
                                }
                            })
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------ recents

    private void showRecents() {
        List<Stores.Recent> list = recents.all();
        if (list.isEmpty()) {
            toast("No recent calls from Orbit Dial yet");
            return;
        }
        String[] items = new String[list.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            Stores.Recent r = list.get(i);
            String who = r.label.isEmpty() ? format(r.number) : r.label + "  " + format(r.number);
            items[i] = who + "\n" + DateUtils.getRelativeTimeSpanString(r.time, now, DateUtils.MINUTE_IN_MILLIS);
        }
        new AlertDialog.Builder(this)
                .setTitle("Recents")
                .setItems(items, (d, which) -> {
                    number.setLength(0);
                    number.append(list.get(which).number);
                    refreshNumber();
                    hint("Tap CALL to dial", ACCENT);
                })
                .setNeutralButton("Clear", (d, w) -> recents.clear())
                .setNegativeButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------ calling

    private void placeCall(String num, String label) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startCall(num, label, Intent.ACTION_CALL);
        } else {
            pendingNumber = num;
            pendingLabel = label;
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL);
        }
    }

    private void startCall(String num, String label, String action) {
        recents.add(num, label);
        Intent i = new Intent(action, Uri.fromParts("tel", num, null));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            if (Intent.ACTION_CALL.equals(action)) {
                startCall(num, label, Intent.ACTION_DIAL);
            } else {
                toast("This device can't place calls");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CALL || pendingNumber == null) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        // Without permission we hand the number to the system dialer instead.
        startCall(pendingNumber, pendingLabel, granted ? Intent.ACTION_CALL : Intent.ACTION_DIAL);
        pendingNumber = null;
        pendingLabel = null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
