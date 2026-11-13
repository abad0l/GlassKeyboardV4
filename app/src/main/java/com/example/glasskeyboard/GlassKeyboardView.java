package com.example.glasskeyboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Custom-drawn "liquid glass" keyboard surface. */
public class GlassKeyboardView extends View {

    public interface Listener {
        void onKey(int code, String text);     // text != null for characters / emoji
        void onDeleteRepeat();
        void onSuggestion(int slot);
        void onGlobe(boolean longPress);
        void onMic();
        void onCursorMove(int delta);
        void onFeedback();
        void onClip(String text);
        void onClipClear();
        void onClipPin(int historyIndex);
        void onSavedDelete(int savedIndex);
    }

    private static final int R_NONE = 0, R_KEY = 1, R_SUGG = 2, R_TB_L = 3, R_TB_R = 4, R_GRID = 5, R_TAB = 6, R_TB_C = 7, R_CLIPBTN = 8;

    private static final class Ptr {
        boolean active;
        int region;
        Layouts.Key key;
        float downX, downY, lastX, scrollStart;
        boolean longPressed, swiped, scrolling, fired;
        String[] alts;
        int altSel, slot = -1;
    }

    private Listener listener;
    private KbTheme theme = KbTheme.get(0);
    private List<List<Layouts.Key>> rows = new ArrayList<>();
    private boolean arabic, popupEnabled = true, animated = true, emojiMode, clipMode;
    private int oneHand;                 // 0 off, 1 left, 2 right
    private float fontScale = 1f;
    private List<String> clips = new ArrayList<>();
    private List<String> saved = new ArrayList<>();
    private int clipTab;                 // 0 = history, 1 = saved texts
    private Bitmap bgBmp;
    private float bgDim = 0.3f;
    private int spaceMode;               // 0 swipe = switch language, 1 swipe = move cursor
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int page, shift, emojiTab;
    private float heightScale = 1f, emojiScroll;
    private String enterLabel = "";
    private boolean enterAccent;
    private final String[] sugg = new String[3];

    private final float d;
    private final Ptr[] ptrs = new Ptr[10];
    private int lpId = -1, repeatId = -1, pressedTb = 0;
    private final int slop;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();
    private Shader blobA, blobB, blobC;

    public GlassKeyboardView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        for (int i = 0; i < ptrs.length; i++) ptrs[i] = new Ptr();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(dp(1.8f));
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private float dp(float v) { return v * d; }

    // ------------------------------------------------------------------ public API
    public void setListener(Listener l) { listener = l; }
    public void setKbTheme(KbTheme t) { theme = t; buildShaders(); invalidate(); }
    public void setHeightScale(float s) { heightScale = s; requestLayout(); buildShaders(); layoutKeys(); invalidate(); }
    public void setPopupEnabled(boolean b) { popupEnabled = b; }
    public void setEnterLabel(String s, boolean accent) { enterLabel = s; enterAccent = accent; invalidate(); }
    public void setShift(int s) { shift = s; invalidate(); }
    public void setEmojiMode(boolean b) { emojiMode = b; if (b) clipMode = false; emojiScroll = 0; invalidate(); }
    public void setClipMode(boolean b) { clipMode = b; if (b) emojiMode = false; emojiScroll = 0; invalidate(); }
    public void setClips(List<String> l) { clips = l == null ? new ArrayList<String>() : l; invalidate(); }
    public void setSaved(List<String> l) { saved = l == null ? new ArrayList<String>() : l; invalidate(); }
    public void setSpaceMode(int m) { spaceMode = m; }
    public void setBgImage(Bitmap b, float dim) {
        bgBmp = b; bgDim = dim;
        removeCallbacks(ticker);
        if (animated && bgBmp == null && isAttachedToWindow()) post(ticker);
        invalidate();
    }
    public void setFontScale(float f) { fontScale = f; invalidate(); }
    public void setOneHand(int m) { oneHand = m; layoutKeys(); invalidate(); }
    private boolean panel() { return emojiMode || clipMode; }
    public boolean isEmojiMode() { return emojiMode; }

    public void setLayout(List<List<Layouts.Key>> r, boolean ar, int pg) {
        rows = r; arabic = ar; page = pg;
        buildShaders();          // key height depends on the number of rows
        layoutKeys();
        invalidate();
    }

    public void setSuggestions(String a, String b, String c) {
        sugg[0] = a; sugg[1] = b; sugg[2] = c;
        invalidate();
    }

    public void setAnimated(boolean a) {
        animated = a;
        removeCallbacks(ticker);
        if (a && bgBmp == null && isAttachedToWindow()) post(ticker);
        invalidate();
    }

    // ------------------------------------------------------------------ geometry
    private float baseKeyH() { return dp(46) * heightScale; }
    private float baseStep() { return baseKeyH() + dp(8); }
    private float areaH() { return 5 * baseStep(); }                      // the key area always has the height of 5 rows
    private float rowStep() { return areaH() / Math.max(1, rows.size()); }
    private float keyH() { return rowStep() - dp(8); }
    private float keysTop() { return dp(56); }
    private float barTop() { return keysTop() + 4 * baseStep(); }         // bottom bar of the emoji / clipboard panels

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (keysTop() + areaH() + dp(4)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        buildShaders();
        layoutKeys();
    }

    private void layoutKeys() {
        int fullW = getWidth();
        if (fullW == 0 || rows.isEmpty()) return;
        int w = oneHand == 0 ? fullW : (int) (fullW * 0.84f);
        float xOff = oneHand == 2 ? fullW - w : 0f;
        float pad = dp(4), gap = dp(6);
        float avail = w - 2 * pad;
        float refUnit = (avail - 9 * gap) / 10f;          // width of a key in a 10-key row
        for (int i = 0; i < rows.size(); i++) {
            List<Layouts.Key> r = rows.get(i);
            float sum = 0;
            boolean hasFunc = false;
            for (Layouts.Key k : r) { sum += k.weight; if (k.type == Layouts.T_FUNC) hasFunc = true; }
            boolean fill = i == rows.size() - 1 || hasFunc || sum >= 9.99f;
            float unit = fill ? (avail - (r.size() - 1) * gap) / sum : refUnit;
            float total = unit * sum + (r.size() - 1) * gap;
            float x = fill ? pad : (w - total) / 2f;
            float y = keysTop() + i * rowStep();
            for (Layouts.Key k : r) {
                k.x = x + xOff; k.y = y; k.w = unit * k.weight; k.h = keyH();
                x += k.w + gap;
            }
        }
    }

    private Layouts.Key keyAt(float x, float y) {
        if (rows.isEmpty()) return null;
        int i = (int) ((y - keysTop() + dp(4)) / rowStep());
        i = Math.max(0, Math.min(rows.size() - 1, i));
        List<Layouts.Key> r = rows.get(i);
        Layouts.Key best = r.get(0);
        float bd = Float.MAX_VALUE;
        for (Layouts.Key k : r) {
            float cx = k.x + k.w / 2f;
            float dist = Math.abs(x - cx) - k.w / 2f;
            if (dist < bd) { bd = dist; best = k; }
        }
        return best;
    }

    private int regionAt(float x, float y) {
        if (y < keysTop() - dp(4)) {
            if (panel()) return R_TAB;
            return x < dp(10) + dp(46) ? R_CLIPBTN : R_SUGG;
        }
        if (panel()) {
            if (y >= barTop()) {
                if (x < getWidth() / 3f) return R_TB_L;
                if (x > getWidth() * 2f / 3f) return R_TB_R;
                return R_TB_C;
            }
            return R_GRID;
        }
        return R_KEY;
    }

    // ------------------------------------------------------------------ colours
    private int a(int color, float alpha) {
        return (color & 0x00FFFFFF) | ((int) (Math.max(0, Math.min(1, alpha)) * 255) << 24);
    }

    private void buildShaders() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        bg.setShader(new LinearGradient(0, 0, 0, h, new int[]{theme.bgTop, theme.bgMid, theme.bgBottom},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        float ba = theme.light ? 0.5f : 0.62f;
        blobA = new RadialGradient(0, 0, w * 0.75f, a(theme.blobA, ba), a(theme.blobA, 0), Shader.TileMode.CLAMP);
        blobB = new RadialGradient(0, 0, w * 0.6f, a(theme.blobB, ba), a(theme.blobB, 0), Shader.TileMode.CLAMP);
        blobC = new RadialGradient(0, 0, w * 0.5f, a(theme.blobC, theme.light ? 0.9f : 0.42f), a(theme.blobC, 0), Shader.TileMode.CLAMP);
        gloss.setShader(new LinearGradient(0, 0, 0, keyH(), a(Color.WHITE, theme.light ? 0.65f : 0.22f),
                a(Color.WHITE, 0.02f), Shader.TileMode.CLAMP));
    }

    // ------------------------------------------------------------------ animation
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (animated && bgBmp == null && isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
                invalidate();
                postDelayed(this, 40);
            }
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(ticker);
        if (animated && bgBmp == null) post(ticker);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        removeCallbacks(longPress);
        removeCallbacks(repeat);
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        removeCallbacks(ticker);
        if (v == VISIBLE && animated && bgBmp == null) post(ticker);
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        c.drawRect(0, 0, w, h, bg);
        if (bgBmp != null) {
            drawBgImage(c, w, h);
        } else {
            drawBlobs(c, w, h);
            if (!theme.light) {
                fill.setShader(null);
                fill.setColor(a(Color.BLACK, 0.18f));
                c.drawRect(0, h * 0.55f, w, h, fill);
            }
        }
        if (clipMode) {
            drawClipHeader(c, w);
            drawClipList(c, w);
        } else if (emojiMode) {
            drawTabs(c, w);
            drawGrid(c, w);
        } else {
            drawSuggestionBar(c, w);
            drawKeys(c);
        }
        if (panel()) drawPanelBar(c, w);
        drawPopups(c, w);
    }

    private void drawBlobs(Canvas c, int w, int h) {
        double t = animated ? SystemClock.uptimeMillis() : 0;
        blob.setShader(blobA);
        c.save(); c.translate((float) (w * (0.28 + 0.12 * Math.sin(t / 3500.0))), (float) (h * (0.02 + 0.06 * Math.cos(t / 4300.0))));
        c.drawCircle(0, 0, w * 0.75f, blob); c.restore();
        blob.setShader(blobB);
        c.save(); c.translate((float) (w * (0.80 + 0.10 * Math.cos(t / 4100.0))), (float) (h * (0.10 + 0.05 * Math.sin(t / 3100.0))));
        c.drawCircle(0, 0, w * 0.6f, blob); c.restore();
        blob.setShader(blobC);
        c.save(); c.translate((float) (w * (0.06 + 0.04 * Math.sin(t / 5200.0))), -h * 0.04f);
        c.drawCircle(0, 0, w * 0.5f, blob); c.restore();
        blob.setShader(null);
    }

    private int textColor() { return theme.text; }

    private void glassRect(Canvas c, float x, float y, float w, float h, float r, float fillAlpha, boolean shadow) {
        if (shadow) {
            fill.setShader(null);
            fill.setColor(a(Color.BLACK, theme.light ? 0.10f : 0.22f));
            rf.set(x, y + dp(1.5f), x + w, y + h + dp(1.5f));
            c.drawRoundRect(rf, r, r, fill);
        }
        fill.setShader(null);
        fill.setColor(a(theme.keyTint, fillAlpha));
        rf.set(x, y, x + w, y + h);
        c.drawRoundRect(rf, r, r, fill);
        c.save();
        c.translate(x, y);
        gloss.setAlpha(255);
        rf.set(0, 0, w, h);
        c.scale(1f, h / keyH());
        c.drawRoundRect(rf.left, rf.top, rf.right, keyH(), r, r * keyH() / h, gloss);
        c.restore();
        stroke.setColor(a(theme.light ? Color.WHITE : theme.keyTint, theme.light ? 0.9f : 0.20f));
        rf.set(x, y, x + w, y + h);
        c.drawRoundRect(rf, r, r, stroke);
    }

    private void drawBgImage(Canvas c, int w, int h) {
        int bw = bgBmp.getWidth(), bh = bgBmp.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float scale = Math.max((float) w / bw, (float) h / bh);
        float sw = w / scale, sh = h / scale;
        int sx = (int) ((bw - sw) / 2f), sy = (int) ((bh - sh) / 2f);
        srcR.set(Math.max(0, sx), Math.max(0, sy), Math.min(bw, (int) (sx + sw)), Math.min(bh, (int) (sy + sh)));
        dstR.set(0, 0, w, h);
        c.drawBitmap(bgBmp, srcR, dstR, bmpPaint);
        fill.setShader(null);
        fill.setColor(a(Color.BLACK, bgDim));
        c.drawRect(0, 0, w, h, fill);
    }

    private void drawSuggestionBar(Canvas c, int w) {
        float l = dp(10), t = dp(7), r = w - dp(10), b = t + dp(40);
        float rad = (b - t) / 2f;
        glassRect(c, l, t, r - l, b - t, rad, theme.light ? 0.45f : 0.11f, false);
        float cb = l + dp(46);                       // end of the clipboard button
        float slotW = (r - cb) / 3f;
        stroke.setColor(a(textColor(), 0.18f));
        c.drawLine(cb, t + dp(9), cb, b - dp(9), stroke);
        icon.setColor(a(textColor(), 0.88f));
        drawClipIcon(c, l + dp(23), (t + b) / 2f, dp(8.5f));
        // highlighted centre slot, like the reference design
        rf.set(cb + slotW + dp(3), t + dp(3), cb + 2 * slotW - dp(3), b - dp(3));
        fill.setShader(null);
        fill.setColor(a(theme.keyTint, theme.light ? 0.75f : 0.17f));
        c.drawRoundRect(rf, rad, rad, fill);
        for (int i = 0; i < 3; i++) {
            String s = sugg[i];
            if (s == null || s.isEmpty()) continue;
            text.setColor(a(textColor(), i == 1 ? 1f : 0.85f));
            text.setTextSize(dp(16));
            text.setFakeBoldText(i == 1);
            float cx = cb + slotW * i + slotW / 2f;
            text.setTextScaleX(1f);
            float tw = text.measureText(s);
            if (tw > slotW - dp(14)) text.setTextScaleX((slotW - dp(14)) / tw);
            c.drawText(s, cx, centerY((t + b) / 2f, text), text);
            text.setTextScaleX(1f);
            text.setFakeBoldText(false);
        }
    }

    private void drawClipIcon(Canvas c, float cx, float cy, float s) {
        rf.set(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 1.0f);
        c.drawRoundRect(rf, s * 0.25f, s * 0.25f, icon);
        rf.set(cx - s * 0.38f, cy - s * 1.05f, cx + s * 0.38f, cy - s * 0.5f);
        c.drawRoundRect(rf, s * 0.2f, s * 0.2f, icon);
        c.drawLine(cx - s * 0.4f, cy + s * 0.05f, cx + s * 0.4f, cy + s * 0.05f, icon);
        c.drawLine(cx - s * 0.4f, cy + s * 0.5f, cx + s * 0.15f, cy + s * 0.5f, icon);
    }

    private float centerY(float cy, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    private boolean isPressed(Layouts.Key k) {
        for (Ptr p : ptrs) if (p.active && p.key == k && p.region == R_KEY && !(p.longPressed && p.alts != null)) return true;
        return false;
    }

    private void drawKeys(Canvas c) {
        float rad = dp(10);
        for (List<Layouts.Key> r : rows) {
            for (Layouts.Key k : r) {
                boolean pr = isPressed(k);
                boolean func = k.type == Layouts.T_FUNC;
                boolean accentKey = k.type == Layouts.T_ENTER && enterAccent;
                float alpha = func || k.type == Layouts.T_ENTER ? 0.22f : 0.14f;
                if (theme.light) alpha = func ? 0.55f : 0.78f;
                if (pr) alpha = theme.light ? 0.45f : 0.36f;
                float scale = pr ? 0.97f : 1f;
                float kw = k.w * scale, kh = k.h * scale;
                glassRect(c, k.x + (k.w - kw) / 2f, k.y + (k.h - kh) / 2f, kw, kh, rad, alpha, !pr);
                if (accentKey) {
                    fill.setShader(null);
                    fill.setColor(a(theme.accent, pr ? 0.95f : 0.8f));
                    rf.set(k.x, k.y, k.x + k.w, k.y + k.h);
                    c.drawRoundRect(rf, rad, rad, fill);
                }
                drawKeyContent(c, k, accentKey);
            }
        }
    }

    private void drawKeyContent(Canvas c, Layouts.Key k, boolean accentOn) {
        float cx = k.x + k.w / 2f, cy = k.y + k.h / 2f;
        int col = accentOn ? Color.WHITE : textColor();
        text.setColor(a(col, 0.95f));
        icon.setColor(a(col, 0.92f));
        float s = dp(10);
        switch (k.code) {
            case Layouts.CODE_SHIFT: drawShift(c, cx, cy, s); return;
            case Layouts.CODE_DEL: drawDel(c, cx, cy, s, icon); return;
            case Layouts.CODE_EMOJI: drawSmiley(c, cx, cy, s * 1.05f); return;
            case Layouts.CODE_TASH: drawTashkeel(c, cx, cy, col); return;
            default: break;
        }
        if (k.type == Layouts.T_SPACE) {
            text.setTextSize(dp(15)); text.setColor(a(col, 0.85f));
            c.drawText(arabic ? "\u0627\u0644\u0639\u0631\u0628\u064A\u0629" : "English (US)", cx, centerY(cy, text), text);
            icon.setColor(a(col, 0.55f));
            drawChevron(c, k.x + dp(22), cy, dp(5), -1);
            drawChevron(c, k.x + k.w - dp(22), cy, dp(5), 1);
            return;
        }
        if (k.type == Layouts.T_ENTER) {
            if (enterLabel == null || enterLabel.isEmpty()) { drawEnterIcon(c, cx, cy, dp(9)); return; }
            text.setTextSize(dp(15));
            c.drawText(enterLabel, cx, centerY(cy, text), text);
            return;
        }
        if (k.type == Layouts.T_FUNC) {
            text.setTextSize(dp(15));
            c.drawText(k.label, cx, centerY(cy, text), text);
            return;
        }
        boolean hasHint = k.mic || (k.hint != null && !k.hint.isEmpty());
        float ly = hasHint ? cy + dp(5) : cy;
        String label = cased(k.label);
        text.setTextSize(dp((arabic ? 22 : 23) * fontScale));
        c.drawText(label, cx, centerY(ly, text), text);
        if (k.mic) {
            icon.setColor(a(col, 0.7f));
            icon.setStrokeWidth(dp(1.3f));
            drawMic(c, cx, k.y + dp(13), dp(6.5f));
            icon.setStrokeWidth(dp(1.8f));
        } else if (hasHint) {
            text.setTextSize(dp(11));
            text.setColor(a(col, 0.55f));
            text.setTextScaleX(1f);
            float tw = text.measureText(k.hint);
            if (tw > k.w - dp(6)) text.setTextScaleX((k.w - dp(6)) / tw);
            c.drawText(k.hint, cx, centerY(k.y + dp(13), text), text);
            text.setTextScaleX(1f);
        }
    }

    private void drawTashkeel(Canvas c, float cx, float cy, int col) {
        float rr = dp(8.5f), oy = cy + dp(6);
        icon.setStyle(Paint.Style.FILL);
        icon.setColor(a(col, 0.8f));
        for (int i = 0; i < 10; i++) {
            double ang = i * Math.PI * 2 / 10;
            c.drawCircle(cx + (float) Math.cos(ang) * rr, oy + (float) Math.sin(ang) * rr, dp(1.15f), icon);
        }
        icon.setStyle(Paint.Style.STROKE);
        text.setTextSize(dp(24));
        text.setColor(a(col, 0.95f));
        c.drawText("\u0640\u064E", cx, centerY(cy - dp(5), text), text);
    }

    private void drawChevron(Canvas c, float cx, float cy, float s, int dir) {
        float tip = cx + dir * s * 0.5f, base = cx - dir * s * 0.5f;
        c.drawLine(base, cy - s, tip, cy, icon);
        c.drawLine(tip, cy, base, cy + s, icon);
    }

    private void drawEnterIcon(Canvas c, float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx + s * 0.9f, cy - s * 0.55f);
        path.lineTo(cx + s * 0.9f, cy + s * 0.2f);
        path.lineTo(cx - s * 0.9f, cy + s * 0.2f);
        c.drawPath(path, icon);
        c.drawLine(cx - s * 0.9f, cy + s * 0.2f, cx - s * 0.35f, cy - s * 0.35f, icon);
        c.drawLine(cx - s * 0.9f, cy + s * 0.2f, cx - s * 0.35f, cy + s * 0.75f, icon);
    }

    private String disp(String s) {
        if (s.length() == 1) {
            char ch = s.charAt(0);
            if ((ch >= 0x064B && ch <= 0x0652) || ch == 0x0670) return "\u0640" + s;
        }
        return s;
    }

    private String cased(String s) {
        return (!arabic && shift > 0 && page == 0) ? s.toUpperCase() : s;
    }

    private void drawShift(Canvas c, float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx, cy - s * 0.65f);
        path.lineTo(cx + s * 0.7f, cy + s * 0.05f);
        path.lineTo(cx + s * 0.28f, cy + s * 0.05f);
        path.lineTo(cx + s * 0.28f, cy + s * 0.6f);
        path.lineTo(cx - s * 0.28f, cy + s * 0.6f);
        path.lineTo(cx - s * 0.28f, cy + s * 0.05f);
        path.lineTo(cx - s * 0.7f, cy + s * 0.05f);
        path.close();
        icon.setStyle(shift > 0 ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        c.drawPath(path, icon);
        icon.setStyle(Paint.Style.STROKE);
        if (shift == 2) c.drawLine(cx - s * 0.28f, cy + s * 0.92f, cx + s * 0.28f, cy + s * 0.92f, icon);
    }

    private void drawDel(Canvas c, float cx, float cy, float s, Paint p) {
        path.reset();
        path.moveTo(cx - s * 0.95f, cy);
        path.lineTo(cx - s * 0.4f, cy - s * 0.6f);
        path.lineTo(cx + s * 0.95f, cy - s * 0.6f);
        path.lineTo(cx + s * 0.95f, cy + s * 0.6f);
        path.lineTo(cx - s * 0.4f, cy + s * 0.6f);
        path.close();
        c.drawPath(path, p);
        c.drawLine(cx - s * 0.05f, cy - s * 0.25f, cx + s * 0.5f, cy + s * 0.25f, p);
        c.drawLine(cx - s * 0.05f, cy + s * 0.25f, cx + s * 0.5f, cy - s * 0.25f, p);
    }

    private void drawSmiley(Canvas c, float cx, float cy, float s) {
        c.drawCircle(cx, cy, s * 0.95f, icon);
        icon.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - s * 0.33f, cy - s * 0.2f, s * 0.11f, icon);
        c.drawCircle(cx + s * 0.33f, cy - s * 0.2f, s * 0.11f, icon);
        icon.setStyle(Paint.Style.STROKE);
        rf.set(cx - s * 0.5f, cy - s * 0.35f, cx + s * 0.5f, cy + s * 0.5f);
        c.drawArc(rf, 25, 130, false, icon);
    }

    private void drawGlobe(Canvas c, float cx, float cy, float s) {
        c.drawCircle(cx, cy, s, icon);
        rf.set(cx - s * 0.45f, cy - s, cx + s * 0.45f, cy + s);
        c.drawOval(rf, icon);
        c.drawLine(cx - s, cy, cx + s, cy, icon);
        c.drawLine(cx, cy - s, cx, cy + s, icon);
        c.drawLine(cx - s * 0.87f, cy - s * 0.5f, cx + s * 0.87f, cy - s * 0.5f, icon);
        c.drawLine(cx - s * 0.87f, cy + s * 0.5f, cx + s * 0.87f, cy + s * 0.5f, icon);
    }

    private void drawMic(Canvas c, float cx, float cy, float s) {
        rf.set(cx - s * 0.38f, cy - s, cx + s * 0.38f, cy + s * 0.25f);
        c.drawRoundRect(rf, s * 0.38f, s * 0.38f, icon);
        rf.set(cx - s * 0.72f, cy - s * 0.45f, cx + s * 0.72f, cy + s * 0.55f);
        c.drawArc(rf, 0, 180, false, icon);
        c.drawLine(cx, cy + s * 0.55f, cx, cy + s * 0.95f, icon);
        c.drawLine(cx - s * 0.35f, cy + s * 0.95f, cx + s * 0.35f, cy + s * 0.95f, icon);
    }

    private void drawPanelBar(Canvas c, int w) {
        float cy = barTop() + baseKeyH() / 2f;
        int col = textColor();
        icon.setColor(a(col, 0.85f));
        text.setTextSize(dp(15));
        text.setColor(a(col, 0.9f));
        glassRect(c, dp(14), cy - dp(17), dp(64), dp(34), dp(17), pressedTb == 1 ? 0.3f : 0.18f, false);
        c.drawText("ABC", dp(46), centerY(cy, text), text);
        glassRect(c, w - dp(78), cy - dp(17), dp(64), dp(34), dp(17), pressedTb == 2 ? 0.3f : 0.18f, false);
        drawDel(c, w - dp(46), cy, dp(9), icon);
        if (clipMode && clipTab == 0) {
            glassRect(c, w / 2f - dp(46), cy - dp(15), dp(92), dp(30), dp(15), pressedTb == 3 ? 0.3f : 0.14f, false);
            text.setTextSize(dp(13));
            text.setColor(a(col, 0.85f));
            c.drawText("\u0645\u0633\u062D \u0627\u0644\u0643\u0644", w / 2f, centerY(cy, text), text);
        }
    }

    // ---- emoji panel
    private float cell() { return getWidth() / 8f; }

    private void drawTabs(Canvas c, int w) {
        float l = dp(10), t = dp(7), r = w - dp(10), b = t + dp(40);
        glassRect(c, l, t, r - l, b - t, dp(20), theme.light ? 0.45f : 0.11f, false);
        int n = Layouts.EMOJI_TABS.length;
        float tw = (r - l) / n;
        text.setTextSize(dp(18));
        for (int i = 0; i < n; i++) {
            if (i == emojiTab) {
                rf.set(l + tw * i + dp(2), t + dp(3), l + tw * (i + 1) - dp(2), b - dp(3));
                fill.setShader(null);
                fill.setColor(a(theme.keyTint, theme.light ? 0.8f : 0.2f));
                c.drawRoundRect(rf, dp(17), dp(17), fill);
            }
            text.setColor(Color.WHITE);
            c.drawText(Layouts.EMOJI_TABS[i], l + tw * i + tw / 2f, centerY((t + b) / 2f, text), text);
        }
    }

    private float gridTop() { return keysTop(); }
    private float gridBottom() { return barTop(); }

    private float clipRowH() { return dp(46); }

    private List<String> curList() { return clipTab == 0 ? clips : saved; }

    private void drawClipHeader(Canvas c, int w) {
        float l = dp(10), t = dp(7), r = w - dp(10), b = t + dp(40);
        glassRect(c, l, t, r - l, b - t, dp(20), theme.light ? 0.45f : 0.11f, false);
        float half = (r - l) / 2f;
        rf.set(l + half * clipTab + dp(3), t + dp(3), l + half * (clipTab + 1) - dp(3), b - dp(3));
        fill.setShader(null);
        fill.setColor(a(theme.keyTint, theme.light ? 0.8f : 0.2f));
        c.drawRoundRect(rf, dp(17), dp(17), fill);
        String[] names = {"\u0627\u0644\u062D\u0627\u0641\u0638\u0629", "\u0627\u0644\u0645\u062D\u0641\u0648\u0638\u0629"};
        text.setTextSize(dp(15));
        for (int i = 0; i < 2; i++) {
            text.setColor(a(textColor(), i == clipTab ? 1f : 0.65f));
            text.setFakeBoldText(i == clipTab);
            c.drawText(names[i], l + half * i + half / 2f, centerY((t + b) / 2f, text), text);
        }
        text.setFakeBoldText(false);
    }

    private void drawPin(Canvas c, float cx, float cy, float s, boolean filled) {
        icon.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        c.drawCircle(cx, cy - s * 0.35f, s * 0.5f, icon);
        icon.setStyle(Paint.Style.STROKE);
        c.drawLine(cx, cy + s * 0.15f, cx, cy + s, icon);
    }

    private void drawClipList(Canvas c, int w) {
        float rh = clipRowH();
        List<String> list = curList();
        c.save();
        c.clipRect(0, gridTop(), w, gridBottom());
        if (list.isEmpty()) {
            text.setTextSize(dp(14));
            text.setColor(a(textColor(), 0.6f));
            String msg = clipTab == 0 ? "\u0644\u0633\u0647 \u0645\u0641\u064A\u0634 \u062D\u0627\u062C\u0629 \u0645\u0646\u0633\u0648\u062E\u0629"
                    : "\u0645\u0641\u064A\u0634 \u0646\u0635\u0648\u0635 \u0645\u062D\u0641\u0648\u0638\u0629 \u2014 \u062F\u0648\u0633 \u0639\u0644\u0649 \u0627\u0644\u062F\u0628\u0648\u0633 \u062C\u0646\u0628 \u0623\u064A \u0646\u0635 \u0645\u0646\u0633\u0648\u062E";
            text.setTextScaleX(1f);
            float tw = text.measureText(msg);
            if (tw > w - dp(30)) text.setTextScaleX((w - dp(30)) / tw);
            c.drawText(msg, w / 2f, (gridTop() + gridBottom()) / 2f, text);
            text.setTextScaleX(1f);
        }
        for (int i = 0; i < list.size(); i++) {
            float y = gridTop() + i * rh - emojiScroll;
            if (y > gridBottom() || y + rh < gridTop()) continue;
            glassRect(c, dp(10), y + dp(3), w - dp(20), rh - dp(6), dp(12), theme.light ? 0.6f : 0.14f, false);
            String full = list.get(i).replace('\n', ' ');
            String s = full.length() > 80 ? full.substring(0, 80) : full;
            text.setTextSize(dp(15));
            text.setColor(a(textColor(), 0.95f));
            text.setTextScaleX(1f);
            float maxW = w - dp(96);
            while (s.length() > 3 && text.measureText(s) > maxW) s = s.substring(0, s.length() - 2);
            if (!s.equals(full)) s = s + "\u2026";
            c.drawText(s, (dp(20) + (w - dp(64))) / 2f, centerY(y + rh / 2f, text), text);
            float ix = w - dp(34), iy = y + rh / 2f;
            icon.setColor(a(textColor(), 0.85f));
            if (clipTab == 0) {
                drawPin(c, ix, iy, dp(7), saved.contains(list.get(i)));
            } else {
                c.drawLine(ix - dp(5), iy - dp(5), ix + dp(5), iy + dp(5), icon);
                c.drawLine(ix - dp(5), iy + dp(5), ix + dp(5), iy - dp(5), icon);
            }
        }
        c.restore();
    }

    private float maxScroll() {
        if (clipMode) return Math.max(0, curList().size() * clipRowH() - (gridBottom() - gridTop()));
        int n = Layouts.EMOJI[emojiTab].length;
        float rowsH = (float) Math.ceil(n / 8.0) * cell();
        return Math.max(0, rowsH - (gridBottom() - gridTop()));
    }

    private void drawGrid(Canvas c, int w) {
        String[] list = Layouts.EMOJI[emojiTab];
        float cs = cell();
        c.save();
        c.clipRect(0, gridTop(), w, gridBottom());
        text.setTextSize(cs * 0.55f);
        text.setColor(Color.WHITE);
        for (int i = 0; i < list.length; i++) {
            int col = i % 8, row = i / 8;
            float cx = col * cs + cs / 2f;
            float cy = gridTop() + row * cs + cs / 2f - emojiScroll;
            if (cy < gridTop() - cs || cy > gridBottom() + cs) continue;
            c.drawText(list[i], cx, centerY(cy, text), text);
        }
        c.restore();
    }

    // ---- popups
    private void drawPopups(Canvas c, int w) {
        for (Ptr p : ptrs) {
            if (!p.active || p.region != R_KEY || p.key == null) continue;
            Layouts.Key k = p.key;
            if (p.longPressed && p.alts != null) {
                float cw = Math.max(k.w, dp(42)), ch = dp(52);
                float total = cw * p.alts.length;
                float left = Math.max(dp(4), Math.min(w - dp(4) - total, k.x + k.w / 2f - total / 2f));
                float top = Math.max(dp(2), k.y - ch - dp(6));
                glassRect(c, left, top, total, ch, dp(12), 0.0f, true);
                fill.setShader(null);
                fill.setColor(theme.light ? Color.WHITE : a(theme.bgMid, 0.97f));
                rf.set(left, top, left + total, top + ch);
                c.drawRoundRect(rf, dp(12), dp(12), fill);
                for (int i = 0; i < p.alts.length; i++) {
                    if (i == p.altSel) {
                        fill.setColor(theme.accent);
                        rf.set(left + cw * i + dp(3), top + dp(3), left + cw * (i + 1) - dp(3), top + ch - dp(3));
                        c.drawRoundRect(rf, dp(9), dp(9), fill);
                    }
                    text.setTextSize(dp(24));
                    text.setColor(i == p.altSel ? Color.WHITE : textColor());
                    c.drawText(disp(p.alts[i]), left + cw * i + cw / 2f, centerY(top + ch / 2f, text), text);
                }
            } else if (popupEnabled && k.type == Layouts.T_CHAR) {
                float bw = k.w * 1.3f, bh = k.h * 1.45f;
                float left = Math.max(dp(2), Math.min(w - dp(2) - bw, k.x + k.w / 2f - bw / 2f));
                float top = Math.max(dp(2), k.y - bh - dp(2));
                fill.setShader(null);
                fill.setColor(theme.light ? Color.WHITE : a(theme.bgMid, 0.98f));
                rf.set(left, top, left + bw, top + bh);
                c.drawRoundRect(rf, dp(12), dp(12), fill);
                stroke.setColor(a(theme.keyTint, 0.3f));
                c.drawRoundRect(rf, dp(12), dp(12), stroke);
                text.setTextSize(dp(31));
                text.setColor(textColor());
                c.drawText(cased(k.label), left + bw / 2f, centerY(top + bh / 2f, text), text);
            }
        }
    }

    // ------------------------------------------------------------------ touch
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (lpId < 0) return;
            Ptr p = ptrs[lpId];
            if (!p.active) return;
            if (p.region == R_KEY && p.key != null && p.key.mic) {
                p.longPressed = true;
                if (listener != null) { listener.onFeedback(); listener.onMic(); }
                invalidate();
            } else if (p.region == R_KEY && p.key != null && p.key.type == Layouts.T_SPACE) {
                p.longPressed = true;
                if (listener != null) { listener.onFeedback(); listener.onGlobe(spaceMode == 0); }
                invalidate();
            } else if (p.region == R_KEY && p.key != null && p.key.alts != null && p.key.type == Layouts.T_CHAR) {
                String[] raw = p.key.alts.split(" ");
                p.alts = new String[raw.length];
                for (int i = 0; i < raw.length; i++) p.alts[i] = cased(raw[i]);
                p.longPressed = true; p.altSel = 0;
                if (listener != null) listener.onFeedback();
                invalidate();
            }
        }
    };

    private int repeatCount;

    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (repeatId < 0 || !ptrs[repeatId].active) return;
            if (listener != null) listener.onDeleteRepeat();
            repeatCount++;
            postDelayed(this, repeatCount > 12 ? 30 : 55);
        }
    };

    private void startRepeat(int id) {
        repeatId = id;
        repeatCount = 0;
        removeCallbacks(repeat);
        postDelayed(repeat, 380);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked(), idx = e.getActionIndex();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(e.getPointerId(idx), e.getX(idx), e.getY(idx), false);
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < ptrs.length; i++) if (ptrs[i].active) up(i, 0, 0, true);
                break;
        }
        return true;
    }

    private void down(int id, float x, float y) {
        if (id >= ptrs.length) return;
        Ptr p = ptrs[id];
        p.active = true; p.downX = x; p.downY = y; p.lastX = x;
        p.longPressed = false; p.swiped = false; p.scrolling = false; p.fired = false; p.alts = null; p.slot = -1;
        p.region = regionAt(x, y);
        p.key = null;
        boolean wantLong = false;
        switch (p.region) {
            case R_KEY:
                p.key = keyAt(x, y);
                if (p.key != null) {
                    fb();
                    if (p.key.code == Layouts.CODE_DEL) {
                        listener.onKey(Layouts.CODE_DEL, null);
                        startRepeat(id);
                    } else wantLong = true;
                }
                break;
            case R_CLIPBTN:
                fb();
                break;
            case R_SUGG:
                p.slot = (int) ((x - dp(10) - dp(46)) / ((getWidth() - dp(20) - dp(46)) / 3f));
                p.slot = Math.max(0, Math.min(2, p.slot));
                break;
            case R_TAB:
                if (clipMode) {
                    clipTab = x < getWidth() / 2f ? 0 : 1;
                    emojiScroll = 0;
                    fb();
                    break;
                }
                int n = Layouts.EMOJI_TABS.length;
                int t = (int) ((x - dp(10)) / ((getWidth() - dp(20)) / n));
                emojiTab = Math.max(0, Math.min(n - 1, t));
                emojiScroll = 0;
                fb();
                break;
            case R_GRID:
                p.scrollStart = emojiScroll;
                break;
            case R_TB_L:
                pressedTb = 1; fb();
                break;
            case R_TB_C:
                pressedTb = 3; fb();
                break;
            case R_TB_R:
                pressedTb = 2; fb();
                if (panel()) { listener.onKey(Layouts.CODE_DEL, null); startRepeat(id); }
                break;
        }
        if (wantLong) {
            lpId = id;
            removeCallbacks(longPress);
            postDelayed(longPress, 330);
        }
        invalidate();
    }

    private void move(int id, float x, float y) {
        if (id >= ptrs.length) return;
        Ptr p = ptrs[id];
        if (!p.active) return;
        if (p.longPressed && p.alts != null && p.key != null) {
            float cw = Math.max(p.key.w, dp(42));
            float total = cw * p.alts.length;
            float left = Math.max(dp(4), Math.min(getWidth() - dp(4) - total, p.key.x + p.key.w / 2f - total / 2f));
            p.altSel = Math.max(0, Math.min(p.alts.length - 1, (int) ((x - left) / cw)));
            invalidate();
            return;
        }
        if (p.region == R_KEY && p.key != null) {
            if (p.key.type == Layouts.T_SPACE) {
                if (spaceMode == 1) {
                    if (Math.abs(x - p.downX) > dp(20)) { p.swiped = true; removeCallbacks(longPress); }
                    if (p.swiped && Math.abs(x - p.lastX) > dp(14)) {
                        listener.onCursorMove(x > p.lastX ? 1 : -1);
                        p.lastX = x;
                    }
                } else if (!p.fired && Math.abs(x - p.downX) > dp(48)) {
                    p.fired = true; p.swiped = true;
                    removeCallbacks(longPress);
                    listener.onFeedback();
                    listener.onGlobe(false);
                }
            } else if (p.key.code != Layouts.CODE_DEL && !p.longPressed) {
                Layouts.Key k = keyAt(x, y);
                if (k != null && k != p.key) {
                    p.key = k;
                    removeCallbacks(longPress);
                    lpId = id;
                    postDelayed(longPress, 330);
                    invalidate();
                }
            }
        } else if (p.region == R_GRID) {
            float dy = y - p.downY;
            if (Math.abs(dy) > slop) p.scrolling = true;
            if (p.scrolling) {
                emojiScroll = Math.max(0, Math.min(maxScroll(), p.scrollStart - dy));
                invalidate();
            }
        }
    }

    private void up(int id, float x, float y, boolean cancel) {
        if (id >= ptrs.length) return;
        Ptr p = ptrs[id];
        if (!p.active) return;
        if (lpId == id) { removeCallbacks(longPress); lpId = -1; }
        if (repeatId == id) { removeCallbacks(repeat); repeatId = -1; }
        if (!cancel && listener != null) {
            switch (p.region) {
                case R_KEY:
                    if (p.key == null) break;
                    if (p.longPressed && p.alts != null) {
                        String s = p.alts[p.altSel];
                        listener.onKey(s.codePointAt(0), s);
                    } else if (p.longPressed || p.key.code == Layouts.CODE_DEL || p.swiped) {
                        // already handled
                    } else if (p.key.type == Layouts.T_CHAR) {
                        listener.onKey(p.key.code, cased(p.key.label));
                    } else if (p.key.type == Layouts.T_SPACE) {
                        listener.onKey(Layouts.CODE_SPACE, " ");
                    } else {
                        listener.onKey(p.key.code, null);
                    }
                    break;
                case R_SUGG:
                    if (p.slot >= 0 && sugg[p.slot] != null && !sugg[p.slot].isEmpty()) listener.onSuggestion(p.slot);
                    break;
                case R_CLIPBTN:
                    listener.onKey(Layouts.CODE_CLIP, null);
                    break;
                case R_TB_L:
                    if (panel()) listener.onKey(Layouts.CODE_ABC, null);
                    break;
                case R_TB_R:
                    break;                       // backspace was handled on touch-down
                case R_TB_C:
                    if (clipMode && clipTab == 0) listener.onClipClear();
                    break;
                case R_GRID:
                    if (!p.scrolling && clipMode) {
                        List<String> cl = curList();
                        int ci = (int) ((p.downY - gridTop() + emojiScroll) / clipRowH());
                        if (ci >= 0 && ci < cl.size()) {
                            if (p.downX > getWidth() - dp(60)) {
                                if (clipTab == 0) listener.onClipPin(ci); else listener.onSavedDelete(ci);
                            } else listener.onClip(cl.get(ci));
                        }
                    } else if (!p.scrolling) {
                        String[] list = Layouts.EMOJI[emojiTab];
                        int col = (int) (p.downX / cell());
                        int row = (int) ((p.downY - gridTop() + emojiScroll) / cell());
                        int i = row * 8 + Math.min(7, Math.max(0, col));
                        if (i >= 0 && i < list.length) listener.onKey(0, list[i]);
                    }
                    break;
            }
        }
        p.active = false; p.key = null; p.alts = null;
        pressedTb = 0;
        invalidate();
    }

    private void fb() { if (listener != null) listener.onFeedback(); }
}
