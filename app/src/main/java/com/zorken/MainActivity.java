package com.zorken;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.animation.ObjectAnimator;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    static final int BG = 0xFF000000, CARD = 0xFF1C1C1E, CARD2 = 0xFF2C2C2E, TXT = 0xFFF2F2F2, SUB = 0xFF9A9AA0, DIM = 0xFF5A5A5E,
            GREEN = 0xFFD2F234 , RED = 0xFFFF6B6B, LINE = 0xFF262628, BTN_LIGHT = 0xFFEDEDED;
    static final int FIELD = 0xFF111113, EDGE = 0xFF3A3A3C, BTN_DARK = 0xFF1E1E20;
    static final int ROW_TITLE = 18;
    static final int ON_GREEN = 0xFF1A2300, ON_LIGHT = 0xFF101010, ON_RED = 0xFF3B0000;
    static final int R_CARD = 24, R_PANEL = 20, R_PILL = 28;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    SharedPreferences sp;
    Typeface SEMI = Typeface.DEFAULT_BOLD, MED = Typeface.DEFAULT, REG = Typeface.DEFAULT;
    ScrollView sv;
    LinearLayout root;
    String pin, rootState = "checking", modState = "checking";
    boolean checked;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Common.prefs(this);
        rootState = Environment.isExternalStorageManager() ? "ok" : "bad";
        modState = sp.getString("st_mod", "checking");
        initFonts();
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        FrameLayout frame = new FrameLayout(this);
        sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), 0, dp(20), dp(24));
        root.setClipToPadding(false);
        root.setClipChildren(false);
        sv.addView(root);
        frame.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            sv.setPadding(0, i.top, 0, i.bottom);
            return ins;
        });
        setContentView(frame);
        gate();
    }

    void initFonts() {
        Typeface base = Typeface.DEFAULT;
        try { base = Typeface.createFromAsset(getAssets(), "fonts/ui.ttf"); } catch (Throwable ignored) {}
        REG = Typeface.create(base, 400, false);
        MED = Typeface.create(base, 500, false);
        SEMI = Typeface.create(base, 600, false);
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    GradientDrawable rect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
    TextView text(String s, int size, int color, boolean bold) { return text(s, size, color, bold ? SEMI : REG); }
    TextView text(String s, int size, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }
    Drawable ripple(int fill, int radiusDp, int fg) {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf((fg & 0x00FFFFFF) | 0x30000000),
            rect(fill, radiusDp), rect(Color.WHITE, radiusDp));
    }
    GradientDrawable outline(int fill, int stroke, int radiusDp) {
        GradientDrawable g = rect(fill, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }
    GradientDrawable grad(int c1, int c2, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }
    Drawable plainRipple() {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(Color.WHITE));
    }
    ImageView ico(int res, int sizeDp, int color) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }
    Button button(String s, int fg, Drawable bg, View.OnClickListener l) {
        Button b = new Button(this) { @Override public boolean performClick() { click(); return super.performClick(); } };
        b.setText(s); b.setAllCaps(false); b.setTextColor(fg); b.setTextSize(15);
        b.setTypeface(MED);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }
    Button pill(String s, int bg, int fg, View.OnClickListener l) { return button(s, fg, ripple(bg, R_PILL, fg), l); }
    Button outlineBtn(String s, View.OnClickListener l) {
        Button b = button(s, TXT, new RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF), field(22), rect(Color.WHITE, 22)), l);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(WRAP, dp(42)));
        return b;
    }
    LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        return p;
    }
    LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }
    LinearLayout vcol() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        return r;
    }
    GradientDrawable field(int radiusDp) { return outline(FIELD, EDGE, radiusDp); }
    void divider(ViewGroup parent) {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        parent.addView(d, new LinearLayout.LayoutParams(MATCH, dp(1)));
    }
    LinearLayout buttonRow(Button... bs) {
        LinearLayout r = hrow();
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, MATCH, 1f);
            if (i > 0) p.leftMargin = dp(6);
            if (i < bs.length - 1) p.rightMargin = dp(6);
            r.addView(bs[i], p);
        }
        return r;
    }
    void recheck() { rootState = "checking"; modState = "checking"; checked = false; main(); }
    FrameLayout toastHost;
    final Handler toastHandler = new Handler(Looper.getMainLooper());
    Runnable toastHide;

    void toast(String s) { toast(s, false); }
    void errorToast(String s) { toast(s, true); }

    void toast(String s, boolean error) {
        if (toastHost == null) {
            toastHost = new FrameLayout(this);
            toastHost.setClickable(false);
            ((ViewGroup) getWindow().getDecorView()).addView(toastHost, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        toastHost.removeAllViews();
        if (toastHide != null) toastHandler.removeCallbacks(toastHide);
        TextView t = text(s, 14, error ? ON_RED : ON_GREEN, true);
        t.setBackground(rect(error ? RED : TXT, 16));
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, WRAP);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(72) + navBarHeight();
        lp.leftMargin = dp(20); lp.rightMargin = dp(20);
        toastHost.addView(t, lp);
        toastHide = () -> { if (toastHost != null) toastHost.removeAllViews(); };
        toastHandler.postDelayed(toastHide, error ? 2600 : 1800);
    }

    int navBarHeight() {
        try {
            WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            if (wi != null) return wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
        } catch (Throwable ignored) {}
        return 0;
    }

    TextView errView() {
        TextView t = text("", 13, RED, true);
        t.setGravity(Gravity.CENTER);
        t.setVisibility(View.GONE);
        return t;
    }
    void setErr(TextView err, String msg) {
        boolean has = msg != null && !msg.isEmpty();
        err.setText(has ? msg : "");
        err.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) shake(err);
    }

    void shake(View v) {
        v.clearAnimation();
        v.setTranslationX(0f);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "translationX", 0f, -18f, 18f, -14f, 14f, -8f, 8f, 0f);
        a.setDuration(380);
        a.start();
    }

    void vibrateWrong() {
        try {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) vib.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) {}
    }
    void buzz(int effect) {
        try {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) vib.vibrate(VibrationEffect.createPredefined(effect));
        } catch (Throwable ignored) {}
    }
    View.OnClickListener h(final View.OnClickListener l) { return v -> { click(); l.onClick(v); }; }
    void tick()  { buzz(VibrationEffect.EFFECT_TICK); }
    void click() { buzz(VibrationEffect.EFFECT_CLICK); }
    Set<String> set(String k) { return new HashSet<>(sp.getStringSet(k, new HashSet<String>())); }

    static final int MATCH_HIDDEN = PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    Drawable icon(String pkg) {
        PackageManager pm = getPackageManager();
        try { return pm.getApplicationIcon(pkg); }
        catch (Exception e) {
            try { return pm.getApplicationInfo(pkg, MATCH_HIDDEN).loadIcon(pm); }
            catch (Exception e2) { return getDrawable(android.R.drawable.sym_def_app_icon); }
        }
    }

    interface SheetAction { void run(Dialog d, Button ok); }

    Dialog sheet(String title, String msg, View body, String posLabel, final SheetAction pos) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout c = vcol();
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.addView(text(title, 20, TXT, true));
        if (msg != null) {
            TextView m = text(msg, 14, SUB, false);
            m.setPadding(0, dp(8), 0, 0);
            c.addView(m);
        }
        if (body != null) {
            c.addView(body, lp(MATCH, WRAP, 14));
        }
        Button cancel = pill(pos == null ? "Close" : "Cancel", CARD2, TXT, v -> d.dismiss());
        LinearLayout row;
        if (pos == null) row = buttonRow(cancel);
        else {
            final Button ok = pill(posLabel, GREEN, ON_GREEN, null);
            ok.setOnClickListener(v -> pos.run(d, ok));
            row = buttonRow(cancel, ok);
        }
        c.addView(row, lp(MATCH, dp(46), 18));
        d.setContentView(c);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new InsetDrawable(outline(CARD, CARD2, R_PILL), dp(16)));
        w.setLayout(MATCH, WRAP);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        return d;
    }

    ScrollView scroller(View content) {
        final int max = getResources().getDisplayMetrics().heightPixels * 55 / 100;
        ScrollView v = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
            // Keep the outer page scroller from stealing vertical drags while this box can still scroll.
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                    boolean can = canScrollVertically(-1) || canScrollVertically(1);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(can);
                }
                return super.dispatchTouchEvent(e);
            }
        };
        v.setNestedScrollingEnabled(true);
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        v.addView(content);
        return v;
    }

    static final int NEW = 0, LOCAL = 1, RESTORE = 2;
    static final Object CALL_LOCK = new Object();

    void gate() {
        if (sp.getString("hash", null) != null) { pinScreen(LOCAL); return; }
        screenHeader("Looking for saved settings…");
        new Thread(() -> {
            boolean ok = pingModule();
            final int ver = ok ? sp.getInt("modver", 0) : -1;
            final boolean has = ok && sp.getBoolean("modhas", false);
            runOnUiThread(() -> {
                if (ver >= Common.MODULE_VER) pinScreen(has ? RESTORE : NEW);
                else moduleUnavailable(ver);
            });
        }).start();
    }

    void moduleUnavailable(int ver) {
        screenHeader(ver < 0
            ? "The module is not responding.\nIf you used Zorken before, enable the module in LSPosed (scope: System Framework) and reboot. Your PIN and settings will then be restored here."
            : "The running module is an older version.\nReboot your phone so the updated module loads. Your PIN and settings will then be restored here.");
        root.addView(pill("Try again", GREEN, ON_GREEN, v -> gate()), lp(MATCH, dp(52), 32));
        root.addView(pill("Set up as new", CARD2, TXT, v -> pinScreen(NEW)), lp(MATCH, dp(52), 12));
    }

    void screenHeader(String subtitle) {
        root.removeAllViews();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text("Zorken", 32, TXT, true);
        t.setGravity(Gravity.CENTER); t.setPadding(0, dp(60), 0, dp(8));
        root.addView(t);
        TextView s = text(subtitle, 15, SUB, false);
        s.setGravity(Gravity.CENTER);
        root.addView(s);
    }

    boolean pingModule() {
        long t0 = System.currentTimeMillis();
        send("ping");
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(100); } catch (Exception ignored) {}
            if (sp.getLong("pong", 0) >= t0) return true;
        }
        return false;
    }

    EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(SUB); e.setTextColor(TXT); e.setTextSize(18);
        e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setTypeface(MED);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PIN_LEN)});
        e.setBackground(outline(CARD2, EDGE, R_PANEL));
        e.setPadding(dp(16), dp(16), dp(16), dp(16));
        return e;
    }

    static final int PIN_LEN = 4;
    static final int KEY = 0xFFFFFFFF, KEY_TXT = 0xFF000000, DOT_OFF = 0xFF5C5C60;

    void drawDots(LinearLayout row, int filled) {
        row.removeAllViews();
        for (int i = 0; i < PIN_LEN; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (i < filled) { g.setColor(GREEN); g.setStroke(dp(2), GREEN); }
            else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), DOT_OFF); }
            d.setBackground(g);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(16), dp(16));
            p.leftMargin = p.rightMargin = dp(11);
            row.addView(d, p);
        }
    }

    Drawable circleRipple(int fill, int rippleColor) {
        GradientDrawable f = new GradientDrawable(); f.setShape(GradientDrawable.OVAL); f.setColor(fill);
        GradientDrawable m = new GradientDrawable(); m.setShape(GradientDrawable.OVAL); m.setColor(Color.WHITE);
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(rippleColor), f, m);
    }

    FrameLayout keyCell(View key) {
        FrameLayout c = new FrameLayout(this);
        c.addView(key, new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER));
        return c;
    }

    void pinScreen(final int mode) {
        root.removeAllViews();
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        final StringBuilder buf = new StringBuilder();
        final Runnable[] submit = new Runnable[1];
        final boolean[] busy = {false};
        final String[] first = {null};

        root.addView(new View(this), new LinearLayout.LayoutParams(MATCH, dp(48), 1f));

        root.addView(ico(R.drawable.ic_lock_pin, 32, GREEN), lp(dp(32), dp(32), 0));
        final TextView title = text(mode == NEW ? "Create PIN" : "Enter PIN", 28, TXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(MATCH, WRAP, 18));
        String sub = mode == NEW ? "Use a different one from your phone lock."
            : mode == RESTORE ? "Saved settings found. Enter your PIN to restore them." : null;
        if (sub != null) {
            TextView s = text(sub, 14, SUB, false);
            s.setGravity(Gravity.CENTER);
            root.addView(s, lp(MATCH, WRAP, 8));
        }

        final LinearLayout dots = hrow();
        dots.setGravity(Gravity.CENTER);
        drawDots(dots, 0);
        root.addView(dots, lp(MATCH, WRAP, 44));

        final TextView err = errView();
        FrameLayout errBox = new FrameLayout(this);
        errBox.addView(err, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        root.addView(errBox, lp(MATCH, dp(24), 14));

        final Runnable redraw = () -> drawDots(dots, buf.length());
        final Runnable wrong = () -> {
            buf.setLength(0);
            drawDots(dots, 0);
            shake(dots);
            vibrateWrong();
        };

        View.OnClickListener digit = v -> {
            if (busy[0] || buf.length() >= PIN_LEN) return;
            setErr(err, null);
            buf.append(((TextView) v).getText());
            tick();
            redraw.run();
            if (buf.length() == PIN_LEN) v.postDelayed(() -> submit[0].run(), 120);
        };

        LinearLayout pad = vcol();
        pad.setPadding(dp(32), 0, dp(32), 0);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = hrow();
            for (String k : rows[r]) {
                TextView key = numKey(k, digit);
                row.addView(keyCell(key), new LinearLayout.LayoutParams(0, WRAP, 1f));
            }
            pad.addView(row, lp(MATCH, WRAP, r == 0 ? 0 : 14));
        }
        LinearLayout last = hrow();
        last.addView(new View(this), new LinearLayout.LayoutParams(0, dp(58), 1f));
        last.addView(keyCell(numKey("0", digit)), new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_backspace);
        back.setColorFilter(SUB);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(circleRipple(Color.TRANSPARENT, 0x33FFFFFF));
        back.setClickable(true);
        back.setContentDescription("Delete");
        back.setOnClickListener(v -> {
            if (!busy[0] && buf.length() > 0) { tick(); buf.setLength(buf.length() - 1); setErr(err, null); redraw.run(); }
        });
        back.setOnLongClickListener(v -> { if (busy[0]) return true; click(); buf.setLength(0); setErr(err, null); redraw.run(); return true; });
        last.addView(keyCell(back), new LinearLayout.LayoutParams(0, WRAP, 1f));
        pad.addView(last, lp(MATCH, WRAP, 14));
        root.addView(pad, lp(MATCH, WRAP, 6));

        root.addView(new View(this), new LinearLayout.LayoutParams(MATCH, dp(24), 1f));

        submit[0] = () -> {
            final String p = buf.toString();
            if (mode == NEW) {
                if (first[0] == null) {
                    first[0] = p; buf.setLength(0); drawDots(dots, 0);
                    title.setText("Confirm PIN");
                    return;
                }
                if (!p.equals(first[0])) {
                    first[0] = null; wrong.run();
                    title.setText("Create PIN");
                    setErr(err, "PINs don't match");
                    return;
                }
                sp.edit().putString("hash", Common.sha(p)).apply();
                pin = p; checked = false; main(); backgroundSync();
            } else if (mode == LOCAL) {
                if (!Common.sha(p).equals(sp.getString("hash", ""))) { AppLog.add(this, "app", "wrong PIN entered in the app"); wrong.run(); setErr(err, "Wrong PIN"); return; }
                AppLog.add(this, "app", "unlocked");
                pin = p; checked = false; main(); backgroundSync();
            } else {
                busy[0] = true; title.setText("Restoring\u2026");
                pull(p, r -> {
                    if (r == 1) { pin = p; checked = false; toast("Settings restored"); main(); }
                    else {
                        busy[0] = false; title.setText("Enter PIN");
                        wrong.run();
                        setErr(err, r == 0 ? "Wrong PIN" : "Module did not answer. Try again.");
                    }
                });
            }
        };
    }

    TextView numKey(String d, View.OnClickListener l) {
        TextView t = text(d, 26, KEY_TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackground(circleRipple(KEY, 0x33000000));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    void backgroundSync() {
        final String p = pin;
        new Thread(() -> {
            int r = call("config", p, sp.getString("hash", null));
            if (r == 0) runOnUiThread(this::mismatchDialog);
            else if (r == 1) { Shell.ensureBundle(this, p); EncryptJob.schedule(this); }
        }).start();
    }

    void mismatchDialog() {
        final EditText e = pinField("Original PIN");
        final TextView err = errView();
        LinearLayout box = vcol();
        box.addView(e);
        box.addView(err, lp(MATCH, WRAP, 8));
        sheet("Different PIN found",
            "The module still remembers another PIN (from before a reinstall). Enter that PIN to restore your saved settings. Until then, changes made here are not applied.",
            box, "Restore", (d, ok) -> {
                setErr(err, null);
                final String p = e.getText().toString();
                if (p.length() != PIN_LEN) { setErr(err, "PIN is 4 digits"); return; }
                ok.setEnabled(false);
                pull(p, r -> {
                    if (r == 1) { pin = p; checked = false; d.dismiss(); toast("Settings restored"); main(); }
                    else { ok.setEnabled(true); setErr(err, r == 0 ? "Wrong PIN" : "Module did not answer. Try again."); if (r == 0) vibrateWrong(); }
                });
            });
    }

    void pull(final String p, final java.util.function.IntConsumer done) {
        new Thread(() -> { final int r = call("pull", p, null); runOnUiThread(() -> done.accept(r)); }).start();
    }

    int call(String cmd, String pinArg, String newhash) {
        synchronized (CALL_LOCK) {
            String nonce = Common.randHex(12);
            sp.edit().putString("pendnonce", nonce).remove("ack").commit();
            send(cmd, pinArg, nonce, newhash);
            for (int i = 0; i < 30; i++) {
                try { Thread.sleep(100); } catch (Exception ignored) {}
                String a = sp.getString("ack", null);
                if (a != null && a.startsWith(nonce + ":")) return a.endsWith(":1") ? 1 : 0;
            }
            sp.edit().remove("pendnonce").apply();
            return -1;
        }
    }

    void main() {
        final int y = sv.getScrollY();
        root.removeAllViews();
        root.setGravity(Gravity.START);
        progShown = false; ring = null; progLabel = null;
        header();
        statusCard();
        final boolean hid = sp.getBoolean("hidden", false);
        wasHidden = hid;
        heroButton(hid);
        settingsList();
        if (!checked) { checked = true; checkStatus(); }
        sv.post(() -> sv.scrollTo(0, y));
    }

    void header() {
        LinearLayout top = hrow();
        top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ImageView more = ico(R.drawable.ic_more, 24, TXT);
        more.setPadding(dp(12), dp(12), dp(12), dp(12));
        more.setBackground(plainRipple());
        more.setContentDescription("More options");
        more.setOnClickListener(h(this::menu));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(48), dp(48));
        mp.rightMargin = -dp(14);
        top.addView(more, mp);
        root.addView(top, new LinearLayout.LayoutParams(MATCH, dp(48)));
        TextView t = text("Zorken", 32, TXT, true);
        t.setPadding(0, dp(12), 0, 0);
        root.addView(t);
    }

    static final String REPO = "https://github.com/syedtahseen/zorken", CONTACT_MAIL = "itxtahseen@gmail.com";

    void menu(View anchor) {
        final int w = dp(210);
        LinearLayout c = vcol();
        c.setBackground(outline(CARD, CARD2, 16));
        c.setPadding(dp(6), dp(6), dp(6), dp(6));
        final PopupWindow pw = new PopupWindow(c, w, WRAP, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setElevation(dp(12));
        menuItem(c, pw, "Re-check status", this::recheck);
        menuItem(c, pw, "Bug report", () -> openLink(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO + "/issues/new?title=" + Uri.encode("Bug: ")
            + "&body=" + Uri.encode("Android " + android.os.Build.VERSION.RELEASE + " / " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\n\nWhat happened:\n"))), "No app found to open the link"));
        menuItem(c, pw, "Contact", () -> openLink(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + CONTACT_MAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "Zorken"), "No email app found"));
        pw.showAsDropDown(anchor, anchor.getWidth() - w, 0);
    }

    void menuItem(LinearLayout c, final PopupWindow pw, String label, final Runnable action) {
        TextView t = text(label, 16, TXT, MED);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        t.setBackground(ripple(Color.TRANSPARENT, 12, TXT));
        t.setOnClickListener(v -> { click(); pw.dismiss(); action.run(); });
        c.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    void openLink(Intent i, String err) {
        try { startActivity(i); } catch (Throwable t) { errorToast(err); }
    }

    void statusCard() {
        boolean good = "ok".equals(rootState) && "ok".equals(modState);
        if ("checking".equals(rootState) || "checking".equals(modState)) { checkingCard(); return; }
        if (!good) { attentionCard("ok".equals(rootState), "ok".equals(modState)); return; }
        LinearLayout c = vcol();
        c.setBackground(grad(0xFF4A5A0F, 0xFF1D2407, 0x55D2F234, R_CARD));
        c.setPadding(dp(24), dp(20), dp(76), dp(20));
        FrameLayout holder = new FrameLayout(this);
        final int cardR = dp(R_CARD);
        holder.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cardR); }
        });
        holder.setClipToOutline(true);
        holder.addView(c, new FrameLayout.LayoutParams(MATCH, WRAP));
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(88), dp(88), Gravity.BOTTOM | Gravity.END);
        ap.rightMargin = -dp(20); ap.bottomMargin = -dp(24);
        holder.addView(new StatusArt(this, GREEN), ap);
        root.addView(holder, lp(MATCH, WRAP, 28));
        c.addView(text("Working", 26, TXT, true));
        TextView sub = text("Ready to protect your data", 15, 0xFFC9D0AE, false);
        sub.setPadding(0, dp(2), 0, 0);
        c.addView(sub);
    }

    void attentionCard(boolean storageOk, boolean moduleOk) {
        LinearLayout c = vcol();
        c.setBackground(outline(0x1FFF6B6B, 0x4DFF6B6B, R_CARD));
        c.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.addView(c, lp(MATCH, WRAP, 28));
        c.addView(text("Attention!", 26, TXT, true));

        boolean first = true;
        if (!moduleOk) { issue(c, "LSPosed module", "old".equals(modState)
            ? "Running an older version. Reboot so the updated module loads (needed for PIN change and restoring settings)."
            : "Not responding. Enable it in LSPosed (scope: System Framework), then reboot.", null); first = false; }
        if (!storageOk) {
            if (!first) divider(c);
            issue(c, "Storage access", "Not granted. Allow \"All files access\" so folders can be hidden.",
                v -> startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
        }
        c.addView(pill("Re-check", BTN_DARK, TXT, v -> recheck()),
            lp(MATCH, dp(46), 14));
    }

    void checkingCard() {
        LinearLayout c = vcol();
        c.setBackground(field(R_CARD));
        c.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.addView(c, lp(MATCH, WRAP, 28));
        c.addView(text("Checking…", 26, TXT, true));
    }

    void issue(LinearLayout c, String title, String desc, View.OnClickListener fix) {
        LinearLayout r = hrow();
        r.setPadding(0, dp(14), 0, dp(14));
        LinearLayout col = vcol();
        col.addView(text(title, 16, TXT, true));
        TextView d = text(desc, 13, SUB, false);
        d.setPadding(0, dp(3), 0, 0);
        col.addView(d);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (fix != null) cp.rightMargin = dp(12);
        r.addView(col, cp);
        if (fix != null) {
            r.addView(ico(R.drawable.ic_chevron, 22, TXT));
            r.setBackground(plainRipple());
            r.setOnClickListener(h(fix));
        }
        c.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    GlowRing ring; TextView progLabel;
    boolean progShown, wasHidden;
    final Handler ticker = new Handler(Looper.getMainLooper());
    final Runnable poll = new Runnable() { public void run() {
        boolean active = progActive();
        if (!active && sp.getBoolean("restore_fail", false)) {
            sp.edit().remove("restore_fail").apply();
            errorToast("Some items could not be restored. See Logs.");
        }
        if (active != progShown || (!active && sp.getBoolean("hidden", false) != wasHidden)) { wasHidden = sp.getBoolean("hidden", false); refresh(); }
        else if (active) progUpdate();
        ticker.postDelayed(this, 500);
    } };

    long lastBusy;
    boolean progActive() {
        long now = System.currentTimeMillis();
        if (Shell.applyActive.get() > 0 || LockService.RUNNING.get() || LockService.RESTORING.get()) { lastBusy = now; return true; }
        return now - lastBusy < 2000;
    }

    void progUpdate() {
        if (ring == null || progLabel == null) return;
        String ph = sp.getString("zk_phase", "");
        long d = sp.getLong("zk_done", 0), t = Math.max(1, sp.getLong("zk_total", 1));
        if (ph.isEmpty()) {
            ring.setProgress(Math.max(0f, ring.value()));
            progLabel.setText("Working…");
            return;
        }
        boolean items = "hide".equals(ph);
        int pct = (int) Math.min(100, d * 100 / t);
        ring.setProgress(Math.min(1f, (float) d / t));
        String what = items ? "Hiding" : "lock".equals(ph) ? "Locking" : "Restoring";
        String amt = items ? d + " of " + t + " items" : (d >> 20) + " / " + (t >> 20) + " MB";
        String l1 = what + " " + pct + "%";
        android.text.SpannableString ss = new android.text.SpannableString(l1 + "\n" + amt);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.72f), l1.length() + 1, ss.length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(DIM), l1.length() + 1, ss.length(), 0);
        progLabel.setText(ss);
    }

    static class StatusArt extends View {
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        StatusArt(Context c, int color) {
            super(c);
            ring.setStyle(Paint.Style.STROKE); ring.setColor(color);
            mark.setStyle(Paint.Style.STROKE); mark.setColor(color);
            mark.setStrokeCap(Paint.Cap.ROUND); mark.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas cv) {
            float R = Math.min(getWidth(), getHeight()) / 2f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float rs = R * 0.16f;
            ring.setStrokeWidth(rs);
            cv.drawCircle(cx, cy, R - rs / 2f, ring);
            mark.setStrokeWidth(R * 0.26f);
            Path p = new Path();
            p.moveTo(cx - 0.54f * R, cy + 0.10f * R);
            p.lineTo(cx - 0.22f * R, cy + 0.38f * R);
            p.lineTo(cx + 0.54f * R, cy - 0.35f * R);
            cv.drawPath(p, mark);
        }
    }

    static class GlowRing extends View {
        final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path(), seg = new Path();
        final float inset;
        float shown = -1, target = -1, pulse = 1f;
        android.animation.ValueAnimator prog, beat;
        GlowRing(Context c, int color, float stroke, float blur, float inset) {
            super(c);
            this.inset = inset;
            for (Paint pt : new Paint[]{base, glow, line}) { pt.setStyle(Paint.Style.STROKE); pt.setStrokeCap(Paint.Cap.ROUND); }
            base.setStrokeWidth(stroke * 0.5f); base.setColor(0x2EFFFFFF);
            line.setStrokeWidth(stroke); line.setColor(color);
            glow.setStrokeWidth(stroke * 1.6f); glow.setColor(color);
            glow.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        }
        float value() { return target; }
        void setProgress(float f) {
            if (f < 0) { target = shown = -1; invalidate(); return; }
            if (Math.abs(f - target) < 0.0005f && shown >= 0) return;
            target = f;
            if (shown < 0) shown = 0;
            if (prog != null) prog.cancel();
            prog = android.animation.ValueAnimator.ofFloat(shown, f);
            prog.setDuration(450);
            prog.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
            prog.start();
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            beat = android.animation.ValueAnimator.ofFloat(0.55f, 1f);
            beat.setDuration(900);
            beat.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            beat.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            beat.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); if (shown > 0) invalidate(); });
            beat.start();
        }
        @Override protected void onDetachedFromWindow() {
            if (beat != null) beat.cancel();
            if (prog != null) prog.cancel();
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas cv) {
            if (shown < 0) return;
            float L = inset, T = inset, R = getWidth() - inset, B = getHeight() - inset, r = (B - T) / 2f;
            path.reset();
            path.moveTo((L + R) / 2f, T);
            path.lineTo(R - r, T);
            path.arcTo(new RectF(R - 2 * r, T, R, B), -90, 180);
            path.lineTo(L + r, B);
            path.arcTo(new RectF(L, T, L + 2 * r, B), 90, 180);
            path.close();
            cv.drawPath(path, base);
            if (shown <= 0.001f) return;
            PathMeasure pm = new PathMeasure(path, false);
            seg.reset();
            pm.getSegment(0, pm.getLength() * Math.min(1f, shown), seg, true);
            glow.setAlpha((int) (255 * 0.85f * pulse));
            cv.drawPath(seg, glow);
            cv.drawPath(seg, line);
        }
    }

    void heroButton(final boolean hid) {
        final int bg = BTN_LIGHT, fg = ON_LIGHT;
        final boolean busy = progActive();
        progShown = busy; ring = null; progLabel = null;
        final int knobH = dp(48), knobW = dp(84), pad = dp(4), m = dp(10);
        final FrameLayout wrap = new FrameLayout(this);
        final GlowRing gr = new GlowRing(this, GREEN, dp(4), dp(6), dp(6));
        wrap.addView(gr, new FrameLayout.LayoutParams(MATCH, MATCH));
        final FrameLayout track = new FrameLayout(this);
        track.setBackground(rect(bg, 28));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(MATCH, MATCH);
        tp.setMargins(m, m, m, m);
        wrap.addView(track, tp);
        LinearLayout.LayoutParams wp = lp(MATCH, dp(56) + 2 * m, 10);
        wp.leftMargin = wp.rightMargin = -m;
        root.addView(wrap, wp);

        if (busy) {
            ring = gr;
            progLabel = text("", 16, fg, SEMI);
            progLabel.setGravity(Gravity.CENTER);
            progLabel.setIncludeFontPadding(false);
            track.addView(progLabel, new FrameLayout.LayoutParams(MATCH, MATCH));
            progUpdate();
            return;
        }

        final String hint = hid ? "Swipe to restore" : "Swipe to hide";
        final TextView label = text(hint, 16, fg, SEMI);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        final int gap = knobW + pad * 2 + dp(8);
        label.setPadding(gap, 0, gap, 0);
        track.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int avail = (r - l) - 2 * gap;
            if (avail <= 0) return;
            Paint pt = new Paint(label.getPaint());
            float size = 16;
            while (size > 10) {
                pt.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size, getResources().getDisplayMetrics()));
                if (pt.measureText(hint) <= avail) break;
                size -= 0.5f;
            }
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
        });
        track.addView(label, new FrameLayout.LayoutParams(MATCH, MATCH));

        final ImageView knob = new ImageView(this);
        knob.setImageResource(R.drawable.ic_chevron);
        knob.setColorFilter(bg);
        knob.setScaleType(ImageView.ScaleType.FIT_CENTER);
        knob.setPadding(dp(12), dp(12), dp(12), dp(12));
        knob.setBackground(rect(fg, 24));
        knob.setContentDescription(hint);
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(knobW, knobH, Gravity.CENTER_VERTICAL | Gravity.START);
        kp.leftMargin = pad;
        track.addView(knob, kp);

        final float[] st = {0, 0};
        final boolean[] passed = {false}, done = {false};
        knob.setOnTouchListener((v, e) -> {
            if (done[0]) return true;
            final float max = track.getWidth() - knobW - 2 * pad;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = v.getTranslationX(); passed[0] = false;
                    v.animate().cancel();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    tick();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float tx = Math.max(0, Math.min(max, st[1] + e.getRawX() - st[0]));
                    v.setTranslationX(tx);
                    float f = max > 0 ? tx / max : 0;
                    label.setAlpha(Math.max(0f, 1f - f * 1.6f));
                    boolean p = f >= 0.9f;
                    if (p && !passed[0]) tick();
                    passed[0] = p;
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    boolean up = e.getActionMasked() == android.view.MotionEvent.ACTION_UP;
                    float f = max > 0 ? v.getTranslationX() / max : 0;
                    if (up && f >= 0.9f) {
                        done[0] = true;
                        click();
                        v.animate().translationX(max).setDuration(80).withEndAction(() -> runHeroAction(hid)).start();
                    } else if (up && Math.abs(e.getRawX() - st[0]) < dp(6)) {
                        v.animate().translationX(dp(28)).setDuration(110)
                            .withEndAction(() -> v.animate().translationX(0).setDuration(160).start()).start();
                    } else {
                        v.animate().translationX(0).setDuration(200).start();
                        label.animate().alpha(1f).setDuration(200).start();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    void runHeroAction(boolean hid) {
        lastBusy = System.currentTimeMillis();
        AppLog.add(this, "app", hid ? "restore started from the app" : "hide started from the app");
        if (hid) {
            send("restore");
            if (!LockService.restore(MainActivity.this, pin)) {
                sp.edit().putBoolean("hidden", false).apply();
                new Thread(() -> {
                    if (Shell.hasProblems(Shell.restoreSync(MainActivity.this, pin)))
                        runOnUiThread(() -> errorToast("Some items could not be restored. See Logs."));
                }).start();
            }
            toast("Restoring…");
        } else {
            send("hide"); sp.edit().putBoolean("hidden", true).apply();
            new Thread(() -> { Shell.hideSync(MainActivity.this); EncryptJob.schedule(MainActivity.this); }).start();
            toast("Hiding…");
        }
        main();
    }

    void settingsList() {
        Set<String> apps = set("apps");
        root.addView(new View(this), new LinearLayout.LayoutParams(MATCH, dp(14)));
        accordion("apps", R.drawable.ic_apps, "Apps to hide", apps.isEmpty() ? "None selected" : apps.size() + " selected", appsBody());
        accordion("files", R.drawable.ic_folder, "Folders & files", filesSummary(), filesBody());
        row(R.drawable.ic_lock, "Wrong attempts", "Before hiding", null, attemptsStepper());
        row(R.drawable.ic_key, "PIN", null, null, outlineBtn("Change PIN", v -> changePin()));
        iconRow();
        displaySizeRow();
        accordion("log", R.drawable.ic_doc, "Logs", "Newest first", logBody());
        row(R.drawable.ic_lock_pin, "About Zorken", "Zorken is an LSPosed module and app that protects your privacy. After a set number of wrong unlock attempts it hides your chosen apps "
            + "and moves your chosen folders and files into an encrypted vault. Only your secret PIN inside this app can bring them back.",
            v -> openLink(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)), "No app found to open the link"), ico(R.drawable.ic_chevron, 22, TXT));
    }

    final Set<String> open = new HashSet<>();

    LinearLayout panel() {
        LinearLayout p = vcol();
        p.setBackground(field(R_PANEL));
        p.setPadding(dp(14), dp(14), dp(14), dp(14));
        return p;
    }

    LinearLayout row(int iconRes, String title, String sub, View.OnClickListener click, View trailing) {
        LinearLayout r = hrow();
        r.setPadding(dp(4), dp(14), dp(4), dp(14));
        r.setMinimumHeight(dp(68));
        r.addView(ico(iconRes, 24, TXT));
        LinearLayout col = vcol();
        col.addView(text(title, ROW_TITLE, TXT, MED));
        if (sub != null) {
            TextView st = text(sub, 15, SUB, false);
            st.setPadding(0, dp(2), 0, 0);
            col.addView(st);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        cp.leftMargin = dp(24);
        if (trailing != null) cp.rightMargin = dp(12);
        r.addView(col, cp);
        if (trailing != null) r.addView(trailing);
        if (click != null) { r.setBackground(plainRipple()); r.setOnClickListener(h(click)); }
        root.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
        divider(root);
        return r;
    }

    void accordion(final String key, int iconRes, String title, String sub, final View body) {
        final ImageView chev = ico(R.drawable.ic_chevron, 22, TXT);
        boolean isOpen = open.contains(key);
        chev.setRotation(isOpen ? 90f : 0f);
        body.setVisibility(isOpen ? View.VISIBLE : View.GONE);
        int at = root.getChildCount();
        row(iconRes, title, sub, v -> {
            boolean o = !open.contains(key);
            if (o) open.add(key); else open.remove(key);
            android.transition.TransitionManager.beginDelayedTransition(root);
            body.setVisibility(o ? View.VISIBLE : View.GONE);
            chev.animate().rotation(o ? 90f : 0f).setDuration(180).start();
        }, chev);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, WRAP);
        bp.bottomMargin = dp(14);
        root.addView(body, at + 1, bp);
    }

    static class Flow extends ViewGroup {
        final int gap;
        Flow(Context c, int gap) { super(c); this.gap = gap; }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                ViewGroup.LayoutParams cl = c.getLayoutParams();
                c.measure(MeasureSpec.makeMeasureSpec(cl.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cl.height, MeasureSpec.EXACTLY));
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > w) { x = 0; y += rowH + gap; rowH = 0; }
                x += cw + gap; rowH = Math.max(rowH, ch);
            }
            setMeasuredDimension(w, y + rowH);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > w) { x = 0; y += rowH + gap; rowH = 0; }
                c.layout(x, y, x + cw, y + ch);
                x += cw + gap; rowH = Math.max(rowH, ch);
            }
        }
    }

    View appsBody() {
        LinearLayout p = panel();
        final PackageManager pm = getPackageManager();
        List<String[]> sel = new ArrayList<>();
        for (String pkg : set("apps")) {
            String label = pkg;
            try { label = String.valueOf(pm.getApplicationInfo(pkg, MATCH_HIDDEN).loadLabel(pm)); } catch (Exception ignored) {}
            sel.add(new String[]{label, pkg});
        }
        Collections.sort(sel, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        if (sel.isEmpty()) {
            p.addView(text("No apps selected yet.", 14, SUB, false));
        } else {
            Flow f = new Flow(this, dp(12));
            for (String[] a : sel) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(icon(a[1]));
                iv.setContentDescription(a[0]);
                iv.setLayoutParams(new ViewGroup.LayoutParams(dp(44), dp(44)));
                f.addView(iv);
            }
            p.addView(f, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        p.addView(pill("Choose apps", BTN_DARK, TXT, v -> pickApps()), lp(MATCH, dp(46), 12));
        return p;
    }

    View filesBody() {
        LinearLayout p = panel();
        final TextView err = errView();
        Set<String> paths = new TreeSet<>(set("paths"));
        if (paths.isEmpty()) p.addView(text("Nothing selected yet.", 14, SUB, false));
        for (final String path : paths) {
            LinearLayout r = hrow();
            r.setPadding(0, dp(8), 0, dp(8));
            String name = path.substring(path.lastIndexOf('/') + 1);
            r.addView(ico(name.contains(".") ? R.drawable.ic_doc : R.drawable.ic_folder, 22, SUB));
            LinearLayout col = vcol();
            col.addView(text(name, 15, TXT, MED));
            col.addView(text(path, 11, SUB, false));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            cp.leftMargin = dp(14);
            r.addView(col, cp);
            ImageView x = ico(R.drawable.ic_close, 20, SUB);
            x.setPadding(dp(8), dp(8), dp(8), dp(8));
            x.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
            x.setBackground(plainRipple());
            x.setContentDescription("Remove");
            x.setOnClickListener(v -> {
                click();
                if (sp.getBoolean("hidden", false)) { setErr(err, "Restore first, then edit the list"); return; }
                Set<String> s2 = set("paths"); s2.remove(path);
                sp.edit().putStringSet("paths", s2).apply(); send("config");
                AppLog.add(this, "settings", "folder/file removed: " + path);
                main();
            });
            r.addView(x);
            p.addView(r);
        }
        p.addView(err, lp(MATCH, WRAP, 6));
        p.addView(buttonRow(
            pill("Add folder", BTN_DARK, TXT, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)),
            pill("Add file", BTN_DARK, TXT, v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 2))),
            lp(MATCH, dp(46), 12));
        return p;
    }

    View logBody() {
        LinearLayout p = panel();
        final TextView lt = text("", 12, SUB, false);
        showLog(lt);
        ScrollView box = scroller(lt);
        p.addView(box);
        p.addView(buttonRow(
            pill("Refresh", BTN_DARK, TXT, v -> { showLog(lt); toast("Logs refreshed"); }),
            pill("Copy", BTN_DARK, TXT, v -> {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Zorken logs", lt.getText()));
                toast("Logs copied");
            }),
            pill("Clear", BTN_DARK, RED, v -> { AppLog.clear(this); showLog(lt); toast("Logs cleared"); })),
            lp(MATCH, dp(46), 12));
        return p;
    }

    void showLog(TextView lt) {
        String all = AppLog.read(this);
        if (all.isEmpty()) { lt.setText("Nothing logged yet."); return; }
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1; i >= 0 && sb.length() < 60000; i--) sb.append(lines[i]).append('\n');
        lt.setText(sb.toString().trim());
    }

    static final String ICON_ALIAS = "com.zorken.LauncherAlias";

    boolean iconHidden() {
        try {
            return getPackageManager().getComponentEnabledSetting(new ComponentName(this, ICON_ALIAS))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) { return false; }
    }

    boolean setIconHidden(boolean hide) {
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, ICON_ALIAS),
                hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
            AppLog.add(this, "icon", hide ? "launcher icon hidden" : "launcher icon shown");
            return true;
        } catch (Throwable t) {
            AppLog.add(this, "icon", "FAILED to " + (hide ? "hide" : "show") + " launcher icon: " + t);
            errorToast("Could not change the icon. See Logs.");
            return false;
        }
    }

    void iconRow() {
        final LinearLayout p = hrow();
        p.setBackground(field(22));
        p.setPadding(dp(4), dp(4), dp(4), dp(4));
        final TextView off = text("Off", 15, SUB, MED), on = text("On", 15, SUB, MED);
        final Runnable paint = () -> {
            boolean h = iconHidden();
            segPaint(off, !h); segPaint(on, h);
        };
        paint.run();
        off.setOnClickListener(v -> {
            if (!iconHidden()) return;
            click();
            setIconHidden(false);
            paint.run();
        });
        on.setOnClickListener(v -> {
            if (iconHidden()) return;
            click();
            setIconHidden(true);
            paint.run();
        });
        for (TextView t : new TextView[]{off, on}) {
            t.setGravity(Gravity.CENTER);
            p.addView(t, new LinearLayout.LayoutParams(dp(48), dp(34)));
        }
        p.setLayoutParams(new LinearLayout.LayoutParams(WRAP, dp(42)));
        row(R.drawable.ic_eye_off, "Hide app icon", "Remove it from the launcher", null, p);
    }

    void segPaint(TextView t, boolean sel) {
        t.setTextColor(sel ? ON_GREEN : SUB);
        t.setBackground(rect(sel ? GREEN : Color.TRANSPARENT, 17));
    }

    String filesSummary() {
        int folders = 0, files = 0;
        for (String p : set("paths")) { if (p.substring(p.lastIndexOf('/') + 1).contains(".")) files++; else folders++; }
        if (folders + files == 0) return "Nothing selected";
        String f = folders == 0 ? "" : folders + (folders == 1 ? " folder" : " folders");
        String i = files == 0 ? "" : files + (files == 1 ? " file" : " files");
        return f.isEmpty() ? i : i.isEmpty() ? f : f + ", " + i;
    }

    LinearLayout stepper(String initial, int valueWidthDp, final java.util.function.IntFunction<String> onStep) {
        LinearLayout p = hrow();
        p.setBackground(field(22));
        p.setPadding(dp(4), dp(4), dp(4), dp(4));
        final TextView val = text(initial, 18, TXT, SEMI);
        val.setGravity(Gravity.CENTER);
        p.addView(step("\u2212", -1, val, onStep));
        p.addView(val, new LinearLayout.LayoutParams(dp(valueWidthDp), WRAP));
        p.addView(step("+", 1, val, onStep));
        p.setLayoutParams(new LinearLayout.LayoutParams(WRAP, dp(42)));
        return p;
    }

    TextView step(String s, final int d, final TextView val, final java.util.function.IntFunction<String> onStep) {
        TextView b = text(s, 22, TXT, MED);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), rect(BTN_DARK, 17), rect(Color.WHITE, 17)));
        b.setOnClickListener(v -> { tick(); val.setText(onStep.apply(d)); });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        return b;
    }

    LinearLayout attemptsStepper() {
        return stepper(String.valueOf(sp.getInt("th", 2)), 34, d -> {
            int n = Common.limit(sp.getInt("th", 2) + d);
            sp.edit().putInt("th", n).apply();
            AppLog.add(MainActivity.this, "settings", "wrong attempts before hiding: " + n);
            send("config");
            return String.valueOf(n);
        });
    }

    static final int SCALE_MIN = 70, SCALE_MAX = 140, SCALE_STEP = 10;

    static Configuration scaledConfig(Context base, int pct) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.densityDpi = Resources.getSystem().getConfiguration().densityDpi * pct / 100;
        return c;
    }

    @Override protected void attachBaseContext(Context base) {
        int pct = Common.prefs(base).getInt("ui_scale", 100);
        if (pct != 100) base = base.createConfigurationContext(scaledConfig(base, pct));
        super.attachBaseContext(base);
    }

    void displaySizeRow() {
        row(R.drawable.ic_eye, "Display size", "Interface scale", null,
            stepper(sp.getInt("ui_scale", 100) + "%", 52, d -> {
                int n = Math.max(SCALE_MIN, Math.min(SCALE_MAX, sp.getInt("ui_scale", 100) + d * SCALE_STEP));
                if (n == sp.getInt("ui_scale", 100)) return n + "%";
                sp.edit().putInt("ui_scale", n).apply();
                AppLog.add(MainActivity.this, "settings", "display size: " + n + "%");
                getResources().updateConfiguration(scaledConfig(MainActivity.this, n), getResources().getDisplayMetrics());
                main();
                return n + "%";
            }));
    }

    void changePin() {
        final EditText cur = pinField("Current PIN"), nw = pinField("New PIN"), cf = pinField("Confirm new PIN");
        final TextView err = errView();
        LinearLayout box = vcol();
        box.addView(cur);
        box.addView(nw, lp(MATCH, WRAP, 10));
        box.addView(cf, lp(MATCH, WRAP, 10));
        box.addView(err, lp(MATCH, WRAP, 8));
        sheet("Change PIN", null, box, "Change", (d, ok) -> {
            setErr(err, null);
            final String o = cur.getText().toString(), n = nw.getText().toString();
            if (!Common.sha(o).equals(sp.getString("hash", ""))) { setErr(err, "Current PIN is wrong"); vibrateWrong(); return; }
            if (n.length() != PIN_LEN) { setErr(err, "New PIN must be 4 digits"); return; }
            if (!n.equals(cf.getText().toString())) { setErr(err, "New PINs don't match"); return; }
            if (n.equals(o)) { setErr(err, "New PIN is the same as the current one"); return; }
            ok.setEnabled(false);
            new Thread(() -> {
                final int r = call("chpin", o, Common.sha(n));
                if (r == 1) Shell.rewrapPin(MainActivity.this, o, n);
                runOnUiThread(() -> {
                    AppLog.add(this, "pin", r == 1 ? "PIN changed" : r == 0 ? "PIN change refused: module remembers a different PIN" : "PIN change failed: module did not answer");
                    if (r == 1) {
                        sp.edit().putString("hash", Common.sha(n)).apply();
                        pin = n; d.dismiss(); toast("PIN changed");
                    } else {
                        ok.setEnabled(true);
                        setErr(err, r == 0 ? "Module remembers a different PIN. Lock and reopen the app to resync."
                                     : "Module did not answer. Check it is active (reboot if you just updated) and try again.");
                    }
                });
            }).start();
        });
    }

    void checkStatus() {
        final String r0 = rootState, m0 = modState;
        rootState = Environment.isExternalStorageManager() ? "ok" : "bad";
        new Thread(() -> {
            boolean ok = pingModule();
            modState = ok ? (sp.getInt("modver", 0) >= Common.MODULE_VER ? "ok" : "old") : "bad";
            sp.edit().putString("st_mod", modState).apply();
            AppLog.add(this, "status", "module: " + modState + ", storage access: " + rootState);
            runOnUiThread(() -> { if (!rootState.equals(r0) || !modState.equals(m0)) refresh(); });
        }).start();
    }

    void refresh() { if (pin != null) main(); }

    @Override protected void onPause() { ticker.removeCallbacks(poll); super.onPause(); }

    @Override protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(poll); ticker.postDelayed(poll, 500);
        checked = false;
        if (pin != null) main();
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        new Thread(() -> EncryptJob.schedule(this)).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        String path = toPath(data.getData(), req == 1);
        if (path == null) { errorToast("Can't use that location (pick from internal storage / SD card)"); return; }
        if (Shell.resolve(path) == null) { errorToast("Can't hide the Android folder, the storage root or Zorken's own vault"); return; }
        Set<String> s = set("paths"); s.add(path);
        sp.edit().putStringSet("paths", s).apply();
        AppLog.add(this, "settings", "folder/file added: " + path);
        send("config");
        if (pin != null) main();
    }

    String toPath(Uri u, boolean tree) {
        try {
            if (!"com.android.externalstorage.documents".equals(u.getAuthority())) return null;
            String id = tree ? DocumentsContract.getTreeDocumentId(u) : DocumentsContract.getDocumentId(u);
            int c = id.indexOf(':');
            if (c < 0 || c == id.length() - 1) return null;
            String vol = id.substring(0, c), rel = id.substring(c + 1);
            if (rel.contains("'")) return null;
            return ("primary".equals(vol) ? "/storage/emulated/0/" : "/storage/" + vol + "/") + rel;
        } catch (Exception e) { return null; }
    }

    void pickApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> ri = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), MATCH_HIDDEN);
        final List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> blocked = new HashSet<>();
        for (ResolveInfo h : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), MATCH_HIDDEN))
            blocked.add(h.activityInfo.packageName);
        String ime = android.provider.Settings.Secure.getString(getContentResolver(), "default_input_method");
        if (ime != null && ime.contains("/")) blocked.add(ime.substring(0, ime.indexOf('/')));
        for (ResolveInfo r : ri) {
            String pkg = r.activityInfo.packageName;
            if (Common.isCoreName(pkg) || blocked.contains(pkg) || !seen.add(pkg)) continue;
            apps.add(new String[]{String.valueOf(r.loadLabel(pm)), pkg});
        }
        for (String pkg : set("apps")) {
            if (Common.isCoreName(pkg) || !seen.add(pkg)) continue;
            String label = pkg;
            try { label = String.valueOf(pm.getApplicationInfo(pkg, MATCH_HIDDEN).loadLabel(pm)); } catch (Exception ignored) {}
            apps.add(new String[]{label, pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        Set<String> sel = set("apps");
        final boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < on.length; i++) on[i] = sel.contains(apps.get(i)[1]);
        LinearLayout list = vcol();
        final View[] rows = new View[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            final int idx = i;
            LinearLayout r = hrow();
            rows[i] = r;
            r.setPadding(0, dp(8), 0, dp(8));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon(apps.get(i)[1]));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.rightMargin = dp(14);
            r.addView(iv, ip);
            LinearLayout names2 = vcol();
            names2.addView(text(apps.get(i)[0], 15, TXT, false));
            TextView pk = text(apps.get(i)[1], 11, SUB, false);
            pk.setPadding(0, dp(1), 0, 0);
            names2.addView(pk);
            r.addView(names2, new LinearLayout.LayoutParams(0, WRAP, 1f));
            final CheckBox cb = new CheckBox(this);
            cb.setChecked(on[i]);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
            cb.setOnCheckedChangeListener((x, c) -> on[idx] = c);
            r.addView(cb);
            r.setOnClickListener(v -> { click(); cb.toggle(); });
            list.addView(r);
        }
        final EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(SUB); search.setTextColor(TXT); search.setTextSize(15); search.setTypeface(MED);
        search.setSingleLine(true);
        search.setBackground(field(22));
        search.setPadding(dp(16), 0, dp(16), 0);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int n) {}
            public void onTextChanged(CharSequence c, int a, int b, int n) {}
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < rows.length; i++)
                    rows[i].setVisibility(q.isEmpty() || apps.get(i)[0].toLowerCase(Locale.ROOT).contains(q)
                        || apps.get(i)[1].toLowerCase(Locale.ROOT).contains(q) ? View.VISIBLE : View.GONE);
            }
        });
        LinearLayout body = vcol();
        body.setFocusableInTouchMode(true); body.requestFocus();
        body.addView(search, new LinearLayout.LayoutParams(MATCH, dp(46)));
        body.addView(scroller(list), lp(MATCH, WRAP, 10));
        sheet("Apps to hide", null, body, "OK", (d, ok) -> {
            Set<String> s2 = set("apps");
            for (String[] a2 : apps) s2.remove(a2[1]);
            for (int i = 0; i < on.length; i++) if (on[i]) s2.add(apps.get(i)[1]);
            sp.edit().putStringSet("apps", s2).apply();
            AppLog.add(this, "settings", "apps to hide: " + s2.size() + " selected");
            send("config");
            d.dismiss();
            main();
        });
    }

    void send(String cmd) {
        send(cmd, pin, null, "config".equals(cmd) ? sp.getString("hash", null) : null);
    }

    void send(String cmd, String pinArg, String nonce, String newhash) {
        Intent i = new Intent("com.zorken.CMD").setPackage("android");
        i.putExtra("cmd", cmd);
        i.putExtra("pin", pinArg);
        i.putExtra("apps", android.text.TextUtils.join(",", set("apps")));
        i.putExtra("paths", android.text.TextUtils.join("\n", set("paths")));
        i.putExtra("threshold", sp.getInt("th", 2));
        if (nonce != null) i.putExtra("nonce", nonce);
        if (newhash != null) i.putExtra("newhash", newhash);
        sendBroadcast(i);
    }
}
