package com.example.glasskeyboard;

import java.util.ArrayList;
import java.util.List;

/** Keyboard layouts (English / Arabic / symbols). */
public final class Layouts {

    public static final int CODE_SHIFT = -1, CODE_DEL = -2, CODE_SYM = -3, CODE_EMOJI = -4,
            CODE_ENTER = -5, CODE_SYM2 = -6, CODE_ABC = -7, CODE_SPACE = 32;

    public static final int CODE_CLIP = -8;

    /** The Arabic "harakat" key: a tap types fatha, a long press offers the other marks. */
    public static final int CODE_TASH = 0x064E;

    /** Settings-driven options (set by the IME before building layouts). */
    public static boolean tashkeel = true;
    public static int digitMode = 0;      // 0 auto (Arabic layout = Hindi digits), 1 always Hindi, 2 always Latin

    private static final String MARKS = "\u064E \u064F \u0650 \u0651 \u0652";

    /** Long-press on an Arabic letter also offers it with fatha / damma / kasra / shadda / sukun. */
    private static List<Key> tash(List<Key> r) {
        if (!tashkeel) return r;
        List<Key> out = new ArrayList<>();
        for (Key k : r) {
            boolean letter = k.type == T_CHAR && k.label.length() == 1
                    && k.label.charAt(0) >= 0x0621 && k.label.charAt(0) <= 0x064A;
            int n = k.alts == null ? 0 : k.alts.split(" ").length;
            if (letter && n <= 2) {
                StringBuilder sb = new StringBuilder();
                if (k.alts != null) sb.append(k.alts).append(' ');
                boolean first = true;
                for (String m : MARKS.split(" ")) {
                    if (!first) sb.append(' ');
                    sb.append(k.label).append(m);
                    first = false;
                }
                Key nk = new Key(k.label, k.code, k.weight, k.type, sb.toString());
                nk.hint = k.hint;          // the small hint on the key stays what the layout says
                nk.mic = k.mic;
                out.add(nk);
            } else out.add(k);
        }
        return out;
    }

    public static final int T_CHAR = 0, T_FUNC = 1, T_SPACE = 2, T_ENTER = 3;

    public static final class Key {
        public final String label;
        public final int code;
        public final float weight;
        public final int type;
        public final String alts;     // long-press alternatives, space separated; the first one is the small hint
        public String hint;           // small text drawn on top of the key (null = none)
        public boolean mic;           // long press starts voice typing instead of a popup
        // filled by the view during layout
        public float x, y, w, h;

        Key(String label, int code, float weight, int type, String alts) {
            this.label = label; this.code = code; this.weight = weight; this.type = type; this.alts = alts;
            if (alts != null) {
                int sp = alts.indexOf(' ');
                this.hint = sp < 0 ? alts : alts.substring(0, sp);
            }
        }
    }

    private static Key c(String s) { return new Key(s, s.codePointAt(0), 1f, T_CHAR, null); }
    private static Key c(String s, String alts) { return new Key(s, s.codePointAt(0), 1f, T_CHAR, alts); }
    private static Key f(String label, int code, float w) { return new Key(label, code, w, T_FUNC, null); }

    private static List<Key> row(String chars, String[] alts) {
        List<Key> r = new ArrayList<>();
        int i = 0;
        for (String s : chars.split(" ")) {
            r.add(alts != null && i < alts.length && alts[i] != null ? c(s, alts[i]) : c(s));
            i++;
        }
        return r;
    }

    /** Joins the non-null parts with spaces (null when nothing is left). */
    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String[] LATIN = "1 2 3 4 5 6 7 8 9 0".split(" ");
    private static String[] HINDI = "\u0661 \u0662 \u0663 \u0664 \u0665 \u0666 \u0667 \u0668 \u0669 \u0660".split(" ");

    /** page: 0 = letters, 1 = symbols, 2 = more symbols. */
    public static List<List<Key>> get(boolean arabic, int page, boolean numberRow) {
        List<List<Key>> rows = new ArrayList<>();
        boolean hindi = digitMode == 1 || (digitMode == 0 && arabic);
        StringBuilder ds = new StringBuilder();
        for (int i = 0; i < 10; i++) { if (i > 0) ds.append(' '); ds.append(hindi ? HINDI[i] : LATIN[i]); }
        String digits = ds.toString();
        if (page == 0) {
            if (numberRow) rows.add(row(digits, null));
            if (arabic) {
                // digit on top of the first row: the "other" digit form when a number row is visible
                String[] a1 = new String[11];
                for (int i = 0; i < 10; i++) {
                    String shown = hindi ? HINDI[i] : LATIN[i];
                    String other = hindi ? LATIN[i] : HINDI[i];
                    a1[i] = numberRow ? other : shown + " " + other;
                }
                a1[10] = "$";
                rows.add(tash(row("\u0636 \u0635 \u062B \u0642 \u0641 \u063A \u0639 \u0647 \u062E \u062D \u062C", a1)));
                rows.add(tash(row("\u0634 \u0633 \u064A \u0628 \u0644 \u0627 \u062A \u0646 \u0645 \u0643 \u0629",
                        new String[]{"@", "#", "\u0649 \u0626", "&", "( \u0644\u0627 \u0644\u0623 \u0644\u0625 \u0644\u0622",
                                "\u0623 \u0625 \u0622 \u0621 \u0671", ")", "-", "+", "\"", "/ \u0647"})));
                List<Key> r3 = new ArrayList<>();
                r3.addAll(row("\u061B \u0638 \u0637 \u0630 \u062F \u0632 \u0631 \u0648",
                        new String[]{": \u061F !", "_", "\u06AF", "\u0626", "\u0621", "\u0625", "\u0622", "\u0624"}));
                Key tk = new Key("\u064E", CODE_TASH, 1f, T_CHAR,
                        "\u064E \u064F \u0650 \u0651 \u0652 \u064B \u064C \u064D \u0640");
                tk.hint = null;
                r3.add(tk);
                r3.add(f("\u232B", CODE_DEL, 1.45f));
                rows.add(tash(r3));
            } else {
                String[] sym1 = {"%", "^", "~", "|", "[", "]", "<", ">", "{", "}"};
                String[] ext1 = {null, null, "\u00E8 \u00E9 \u00EA \u00EB \u0113", null, null, "\u00FD \u00FF",
                        "\u00FB \u00FC \u00F9 \u00FA \u016B", "\u00EE \u00EF \u00ED \u00EC \u012B",
                        "\u00F6 \u00F4 \u00F2 \u00F3 \u00F5 \u014D", null};
                String[] q = new String[10];
                for (int i = 0; i < 10; i++) q[i] = join(sym1[i], numberRow ? null : LATIN[i], ext1[i]);
                rows.add(row("q w e r t y u i o p", q));
                String[] sym2 = {"@", "#", "&", "*", "-", "+", "=", "(", ")"};
                String[] ext2 = {"\u00E0 \u00E1 \u00E2 \u00E4 \u00E3 \u00E5 \u0101", "\u00DF", null, null, null, null, null, null, "\u0142"};
                String[] a = new String[9];
                for (int i = 0; i < 9; i++) a[i] = join(sym2[i], ext2[i]);
                rows.add(row("a s d f g h j k l", a));
                List<Key> r3 = new ArrayList<>();
                r3.add(f("\u21E7", CODE_SHIFT, 1.45f));
                r3.addAll(row("z x c v b n m", new String[]{"_ \u017E", "$", "\" \u00E7 \u0107 \u010D", "'", ":", "; \u00F1 \u0144", "/"}));
                r3.add(f("\u232B", CODE_DEL, 1.45f));
                rows.add(r3);
            }
        } else if (page == 1) {
            rows.add(row(digits, null));
            rows.add(row(arabic ? "- / : \u061B ( ) $ & @ \"" : "- / : ; ( ) $ & @ \"", null));
            List<Key> r3 = new ArrayList<>();
            r3.add(f("#+=", CODE_SYM2, 1.45f));
            r3.addAll(row(arabic ? ". \u060C \u061F ! ' %" : ". , ? ! ' %", null));
            r3.add(f("\u232B", CODE_DEL, 1.45f));
            rows.add(r3);
        } else {
            rows.add(row("[ ] { } # % ^ * + =", null));
            rows.add(row("_ \\ | ~ < > \u20AC \u00A3 \u00A5 \u2022", null));
            List<Key> r3 = new ArrayList<>();
            r3.add(f("123", CODE_SYM, 1.45f));
            r3.addAll(row(". , ? ! ' %", null));
            r3.add(f("\u232B", CODE_DEL, 1.45f));
            rows.add(r3);
        }
        // bottom row:  123 | emoji | comma(+mic) | space | period | enter
        List<Key> b = new ArrayList<>();
        b.add(f(page == 0 ? "123" : "ABC", page == 0 ? CODE_SYM : CODE_ABC, 1f));
        b.add(f("\u263A", CODE_EMOJI, 1f));
        Key comma = c(arabic ? "\u060C" : ",");
        comma.mic = true;
        b.add(comma);
        b.add(new Key("space", CODE_SPACE, 4.7f, T_SPACE, null));
        Key dot = c(".", arabic ? "\u060C \u061F ! : \u061B \u2026 ," : ", ? ! : ; \u2026");
        dot.hint = arabic ? "\u060C!\u061F" : ",!?";
        b.add(dot);
        b.add(new Key("return", CODE_ENTER, 1.5f, T_ENTER, null));
        rows.add(b);
        return rows;
    }

    // ------------------------------------------------------------------ emoji
    public static final String[] EMOJI_TABS = {"😀", "❤️", "👋", "🐱", "🍔", "⚽", "🚗", "💡", "🔣"};

    public static final String[][] EMOJI = {
        {"😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😗","😙","😚","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","😐","😑","😶","😏","😒","🙄","😬","😮‍💨","🤥","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮","🤧","🥵","🥶","😵","🤯","🤠","🥳","😎","🤓","🧐","😕","😟","🙁","☹️","😮","😯","😲","😳","🥺","😦","😧","😨","😰","😥","😢","😭","😱","😖","😣","😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈","👿","💀","💩","🤡","👻","👽","🤖"},
        {"❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟","♥️","😻","💑","💏","🌹","🥀","🌸","💐","🎁","💋","✨","💫","⭐","🌟","🔥","💥","💯","🎉","🎊","🥂","🍾"},
        {"👍","👎","👌","✌️","🤞","🤟","🤘","🤙","👈","👉","👆","👇","☝️","✋","🤚","🖐️","🖖","👋","🤝","🙏","💪","👏","🙌","👐","🤲","✍️","💅","🤳","🙋","🙆","🙅","🤷","🤦","🧏","👀","👁️","👅","👄","🧠","🦾"},
        {"🐱","🐶","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧","🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄","🐝","🐛","🦋","🐌","🐞","🐢","🐍","🦎","🐙","🦑","🦀","🐠","🐬","🐳","🦈","🐘","🦒","🐫","🌵","🌲","🌴","🌱","🍀","🌻","🌞","🌙","⭐","☁️","⚡","❄️","🌈"},
        {"🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🥑","🍆","🥕","🌽","🌶️","🥔","🍞","🧀","🍳","🥞","🥓","🍔","🍟","🍕","🌭","🌮","🌯","🥙","🍝","🍜","🍲","🍣","🍤","🍦","🍩","🍪","🎂","🍰","🍫","🍬","🍭","☕","🍵","🥤","🍺","🍷"},
        {"⚽","🏀","🏈","⚾","🎾","🏐","🏉","🎱","🏓","🏸","🥊","🥋","⛳","🏹","🎣","🎮","🎲","🧩","🎯","🎳","🎤","🎧","🎸","🎹","🥁","🎬","🏆","🥇","🥈","🥉","🏅","🚴","🏊","🏃","🧘","⛷️","🏄"},
        {"🚗","🚕","🚙","🚌","🏎️","🚓","🚑","🚒","🚚","🚜","🏍️","🚲","🛵","🚂","✈️","🚀","🛸","🚁","⛵","🚤","🚢","⚓","🏠","🏢","🏥","🏫","🕌","⛪","🗼","🗽","🌍","🌎","🌏","🗺️","⛰️","🏖️","🏝️","🌋"},
        {"💡","📱","💻","⌨️","🖥️","🖨️","📷","📹","📺","📻","⏰","⌛","🔋","🔌","💰","💳","💎","🔧","🔨","⚙️","🧲","🔑","🔒","🔓","📌","📎","✂️","📝","📚","📖","📅","📊","📈","📉","✉️","📦","🎁","🧹","🧼","🛒"},
        {"✅","❌","⭕","❗","❓","‼️","⚠️","🚫","🔞","♻️","➕","➖","➗","✖️","➡️","⬅️","⬆️","⬇️","↗️","↘️","🔄","🔁","🔔","🔕","🔊","🔇","📣","💬","💭","🗯️","©️","®️","™️","🆗","🆕","🆒","🔝","🔜","🕌","☪️"},
    };
}
