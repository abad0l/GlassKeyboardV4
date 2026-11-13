package com.example.glasskeyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private float d;
    private LinearLayout root;
    private TextView step1State, step2State, bgState;
    private LinearLayout savedBox;
    private static final int REQ_EXPORT = 11, REQ_IMPORT = 12, REQ_BG = 13;
    private static final String SEP = "\u0001";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("glass", MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#4A2377"), Color.parseColor("#1E1730"), Color.parseColor("#14111B")});
        sv.setBackground(bg);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(40), dp(18), dp(30));
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);

        TextView title = text("Glass Keyboard", 28, true);
        root.addView(title);
        root.addView(sub("كيبورد زجاجي بخلفية متحركة، بيدعم العربي والإنجليزي والإيموجي"));

        // ---- setup card
        LinearLayout setup = card();
        setup.addView(text("التفعيل", 17, true));
        step1State = sub("");
        setup.addView(step1State);
        setup.addView(button("١) فعّل الكيبورد", v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        step2State = sub("");
        setup.addView(step2State);
        setup.addView(button("٢) اختار Glass Keyboard", v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }));
        root.addView(setup);

        // ---- test field
        LinearLayout test = card();
        test.addView(text("جرّب هنا", 17, true));
        EditText et = new EditText(this);
        et.setHint("اكتب أي حاجة...");
        et.setHintTextColor(Color.parseColor("#88FFFFFF"));
        et.setTextColor(Color.WHITE);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(2);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setBackground(glass(14, 0x22FFFFFF));
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        test.addView(et, lp);
        root.addView(test);

        // ---- themes
        LinearLayout themes = card();
        themes.addView(text("الثيم", 17, true));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        for (int i = 0; i < KbTheme.ALL.length; i++) row.addView(swatch(i, row));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(12);
        themes.addView(hs, hl);
        root.addView(themes);

        // ---- keyboard background image
        LinearLayout bgc = card();
        bgc.addView(text("خلفية الكيبورد", 17, true));
        bgc.addView(sub("اختار صورة من المعرض تبقى خلفية الكيبورد. تقدر تقفلها في أي وقت وترجع للخلفية المتحركة."));
        bgc.addView(toggle("استخدم صورتي كخلفية", "bg_on", false));
        bgState = sub("");
        bgc.addView(bgState);
        bgc.addView(button("اختار صورة", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_BG);
        }));
        bgc.addView(button("شيل الصورة", v -> {
            new File(getFilesDir(), "kb_bg.jpg").delete();
            prefs.edit().putBoolean("bg_on", false).putLong("bg_stamp", System.currentTimeMillis()).apply();
            Toast.makeText(this, "اتشالت الصورة", Toast.LENGTH_SHORT).show();
            recreate();
        }));
        bgc.addView(sub("تعتيم الصورة (عشان الحروف تبان)"));
        SeekBar dimBar = new SeekBar(this);
        dimBar.setMax(70);
        dimBar.setProgress(prefs.getInt("bg_dim", 30));
        dimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s2, int p, boolean u) {
                prefs.edit().putInt("bg_dim", p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s2) { }
            @Override public void onStopTrackingTouch(SeekBar s2) { }
        });
        bgc.addView(dimBar);
        root.addView(bgc);

        // ---- saved texts
        LinearLayout svc = card();
        svc.addView(text("النصوص المحفوظة", 17, true));
        svc.addView(sub("نصوص بتستخدمها كتير (عنوان، رقم، رسالة جاهزة). بتلاقيها في الكيبورد: اضغط على أيقونة الحافظة فوق ثم المحفوظة، ودوس على النص عشان يتكتب."));
        savedBox = new LinearLayout(this);
        savedBox.setOrientation(LinearLayout.VERTICAL);
        svc.addView(savedBox);
        svc.addView(button("إضافة نص جديد", v -> addSavedDialog()));
        root.addView(svc);
        refreshSaved();

        // ---- options
        LinearLayout opt = card();
        opt.addView(text("الإعدادات", 17, true));
        opt.addView(toggle("اهتزاز عند الضغط", "haptic", true));
        opt.addView(toggle("صوت الضغط", "sound", false));
        opt.addView(toggle("معاينة الحرف المضغوط", "popup", true));
        opt.addView(toggle("اقتراحات الكلمات", "suggest", true));
        opt.addView(toggle("صف الأرقام فوق الحروف", "numrow", true));
        opt.addView(toggle("خلفية متحركة", "anim", true));
        opt.addView(toggle("حرف كبير تلقائي في أول الجملة", "autocap", true));
        opt.addView(toggle("مسافتين = نقطة", "dblspace", true));
        opt.addView(toggle("تشكيل الحروف بالضغط المطوّل (عربي)", "tashkeel", true));
        opt.addView(toggle("حافظة (آخر ٢٠ نسخة)", "clip", true));
        opt.addView(toggle("وضع التخفّي (بلا تعلّم ولا حافظة)", "incognito", false));
        opt.addView(cycle("الأرقام", "digits", new String[]{"تلقائي (هندية في العربي)", "هندية ٠١٢٣ دايماً", "إنجليزية 0123 دايماً"}));
        opt.addView(cycle("سحب مفتاح المسافة", "spaceswipe", new String[]{"يغيّر اللغة", "يحرّك المؤشر"}));
        opt.addView(cycle("وضع اليد الواحدة", "onehand", new String[]{"مقفول", "الكيبورد ناحية الشمال", "الكيبورد ناحية اليمين"}));
        opt.addView(sub("حجم خط المفاتيح"));
        SeekBar fs = new SeekBar(this);
        fs.setMax(40);                       // 80%..120%
        fs.setProgress(prefs.getInt("fontsize", 100) - 80);
        fs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                prefs.edit().putInt("fontsize", 80 + p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        opt.addView(fs);
        opt.addView(sub("ارتفاع الكيبورد"));
        SeekBar sb = new SeekBar(this);
        sb.setMax(40);                       // 80%..120%
        sb.setProgress(prefs.getInt("height", 100) - 80);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                prefs.edit().putInt("height", 80 + p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        opt.addView(sb);
        root.addView(opt);

        // ---- text shortcuts
        LinearLayout sc = card();
        sc.addView(text("اختصارات نصية", 17, true));
        sc.addView(sub("سطر لكل اختصار بالشكل: اختصار=النص. مثال: ايميل=name@mail.com\nبيتحوّل لما تدوس مسافة بعد الاختصار."));
        EditText se = new EditText(this);
        se.setHint("ايميل=name@mail.com");
        se.setHintTextColor(Color.parseColor("#88FFFFFF"));
        se.setTextColor(Color.WHITE);
        se.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        se.setMinLines(3);
        se.setGravity(Gravity.TOP | Gravity.START);
        se.setBackground(glass(14, 0x22FFFFFF));
        se.setPadding(dp(14), dp(12), dp(14), dp(12));
        se.setText(prefs.getString("shortcuts", ""));
        se.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d2) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d2) { }
            @Override public void afterTextChanged(Editable e) { prefs.edit().putString("shortcuts", e.toString()).apply(); }
        });
        sc.addView(se, new LinearLayout.LayoutParams(-1, -2));
        root.addView(sc);

        // ---- dictionary backup
        LinearLayout bk = card();
        bk.addView(text("نسخ احتياطي للقاموس", 17, true));
        bk.addView(sub("صدّر الكلمات اللي الكيبورد اتعلمها لملف، أو استورد ملف قديم."));
        bk.addView(button("تصدير", v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "glass-keyboard-words.txt");
            startActivityForResult(i, REQ_EXPORT);
        }));
        bk.addView(button("استيراد", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        }));
        root.addView(bk);

        LinearLayout tips = card();
        tips.addView(text("نصايح", 17, true));
        tips.addView(sub("• اضغط مطوّلاً على أي حرف عشان تطلع الحروف المشابهة (الإشارة الصغيرة فوق الحرف هي أول اختيار).\n"
                + "• اسحب صباعك على مفتاح المسافة لتغيير اللغة (أو لتحريك المؤشر من الإعدادات).\n"
                + "• ضغطة مطوّلة على الفاصلة = كتابة بالصوت. ضغطة مطوّلة على المسافة = اختيار كيبورد تاني.\n"
                + "• أيقونة الحافظة جنب الاقتراحات بتفتح المنسوخ والنصوص المحفوظة. دوس على الدبوس عشان تحفظ نص.\n"
                + "• اضغط مرتين على ⇧ لتثبيت الحروف الكبيرة.\n"
                + "• مسافتين ورا بعض بتحط نقطة."));
        root.addView(tips);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_EXPORT) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(Suggestions.exportAll(this).getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "تم التصدير", Toast.LENGTH_SHORT).show();
            } else if (req == REQ_BG) {
                saveBackground(uri);
                Toast.makeText(this, "تم تعيين الخلفية", Toast.LENGTH_SHORT).show();
                recreate();
            } else if (req == REQ_IMPORT) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                int n = Suggestions.importAll(this, sb.toString());
                Toast.makeText(this, "تم استيراد " + n + " كلمة", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "حصلت مشكلة في الملف", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void refreshState() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled = false;
        if (imm != null) {
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
                if (i.getPackageName().equals(getPackageName())) enabled = true;
            }
        }
        String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = cur != null && cur.startsWith(getPackageName() + "/");
        step1State.setText(enabled ? "✓ الكيبورد مفعّل" : "الكيبورد لسه مش مفعّل");
        step2State.setText(selected ? "✓ هو الكيبورد الحالي" : "لسه مش الكيبورد الحالي");
        boolean has = new File(getFilesDir(), "kb_bg.jpg").exists();
        bgState.setText(has ? "✓ في صورة محفوظة" : "لسه ما اخترتش صورة");
    }

    // ------------------------------------------------------------------ background image
    private void saveBackground(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        final int max = 1400;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > max * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in2 = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in2, null, o2);
        }
        if (bmp == null) throw new Exception("decode");
        int rot = 0;
        try (InputStream in3 = getContentResolver().openInputStream(uri)) {
            ExifInterface ex = new ExifInterface(in3);
            int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (ori == 6) rot = 90; else if (ori == 3) rot = 180; else if (ori == 8) rot = 270;
        } catch (Exception ignored) { }
        float sc = Math.min(1f, (float) max / Math.max(bmp.getWidth(), bmp.getHeight()));
        Matrix m = new Matrix();
        if (sc < 1f) m.postScale(sc, sc);
        if (rot != 0) m.postRotate(rot);
        if (!m.isIdentity()) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        File f = new File(getFilesDir(), "kb_bg.jpg");
        try (FileOutputStream os = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
        }
        prefs.edit().putBoolean("bg_on", true).putLong("bg_stamp", System.currentTimeMillis()).apply();
    }

    // ------------------------------------------------------------------ saved texts
    private List<String> readSaved() {
        List<String> l = new ArrayList<>();
        String raw = prefs.getString("saved", "");
        if (raw != null && !raw.isEmpty()) for (String c : raw.split(SEP)) if (!c.isEmpty()) l.add(c);
        return l;
    }

    private void writeSaved(List<String> l) { prefs.edit().putString("saved", TextUtils.join(SEP, l)).apply(); }

    private void refreshSaved() {
        savedBox.removeAllViews();
        final List<String> l = readSaved();
        if (l.isEmpty()) { savedBox.addView(sub("مفيش نصوص محفوظة لسه.")); return; }
        for (int i = 0; i < l.size(); i++) {
            final int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(glass(12, 0x14FFFFFF));
            r.setPadding(dp(12), dp(8), dp(8), dp(8));
            TextView t = text(l.get(i), 14, false);
            t.setMaxLines(3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            Button del = new Button(this);
            del.setText("حذف");
            del.setAllCaps(false);
            del.setTextColor(Color.WHITE);
            del.setBackground(glass(12, 0x55FF5C7A));
            del.setOnClickListener(x -> {
                List<String> cur = readSaved();
                if (idx < cur.size()) { cur.remove(idx); writeSaved(cur); }
                refreshSaved();
            });
            r.addView(del, new LinearLayout.LayoutParams(dp(64), dp(40)));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
            lp2.topMargin = dp(6);
            savedBox.addView(r, lp2);
        }
    }

    private void addSavedDialog() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(3);
        in.setGravity(Gravity.TOP | Gravity.START);
        in.setTextColor(Color.WHITE);
        in.setHint("اكتب النص هنا");
        in.setHintTextColor(0x88FFFFFF);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("نص جديد")
                .setView(in)
                .setPositiveButton("حفظ", (dlg, which) -> {
                    String t = in.getText().toString().trim();
                    if (t.isEmpty()) return;
                    List<String> l = readSaved();
                    l.remove(t);
                    l.add(0, t);
                    while (l.size() > 50) l.remove(l.size() - 1);
                    writeSaved(l);
                    refreshSaved();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ------------------------------------------------------------------ small UI helpers
    private int dp(float v) { return (int) (v * d + 0.5f); }

    private GradientDrawable glass(float radiusDp, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), 0x30FFFFFF);
        return g;
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView sub(String s) {
        TextView t = text(s, 14, false);
        t.setTextColor(0xCCFFFFFF);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(glass(22, 0x1AFFFFFF));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(glass(16, 0x55B65CFF));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View toggle(String label, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Color.WHITE);
        s.setTextSize(15);
        s.setChecked(prefs.getBoolean(key, def));
        s.setPadding(0, dp(10), 0, dp(10));
        s.setOnCheckedChangeListener((CompoundButton v, boolean on) -> prefs.edit().putBoolean(key, on).apply());
        return s;
    }

    /** A button that cycles through named options and stores the index. */
    private View cycle(final String label, final String key, final String[] opts) {
        final Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(glass(14, 0x22FFFFFF));
        final int[] cur = {Math.max(0, Math.min(opts.length - 1, prefs.getInt(key, 0)))};
        b.setText(label + ": " + opts[cur[0]]);
        b.setOnClickListener(v -> {
            cur[0] = (cur[0] + 1) % opts.length;
            prefs.edit().putInt(key, cur[0]).apply();
            b.setText(label + ": " + opts[cur[0]]);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private View swatch(final int idx, final LinearLayout parent) {
        KbTheme t = KbTheme.get(idx);
        TextView v = new TextView(this);
        v.setText(t.name);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(12);
        v.setTextColor(t.light ? Color.parseColor("#1D1B2A") : Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), dp(78));
        lp.setMarginEnd(dp(10));
        v.setLayoutParams(lp);
        styleSwatch(v, t, prefs.getInt("theme", 0) == idx);
        v.setOnClickListener(x -> {
            prefs.edit().putInt("theme", idx).apply();
            for (int i = 0; i < parent.getChildCount(); i++) {
                styleSwatch((TextView) parent.getChildAt(i), KbTheme.get(i), i == idx);
            }
        });
        return v;
    }

    private void styleSwatch(TextView v, KbTheme t, boolean selected) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{t.blobA, t.bgTop, t.bgBottom});
        g.setCornerRadius(dp(18));
        g.setStroke(dp(selected ? 3 : 1), selected ? Color.WHITE : 0x40FFFFFF);
        v.setBackground(g);
        v.setText(selected ? "✓ " + t.name : t.name);
    }
}
