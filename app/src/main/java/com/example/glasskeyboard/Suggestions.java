package com.example.glasskeyboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Suggestion engine: big frequency word lists (English + Arabic incl. Egyptian words), typo correction,
 * next-word prediction, and on-device learning of words and word pairs.
 */
public final class Suggestions {

    private static final char SEP = '\u0001';
    private static final long LEARN_BASE = 20_000_000L;
    private static final long LEARN_STEP = 3_000_000L;

    private final WordIndex en = new WordIndex(false);
    private final WordIndex ar = new WordIndex(true);
    private volatile boolean ready;

    private final Map<String, Integer> learned = new HashMap<>();
    private final Map<String, Integer> learnedBi = new HashMap<>();
    private final SharedPreferences prefs;
    private final SharedPreferences biPrefs;

    public Suggestions(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        prefs = ctx.getSharedPreferences("glass_words", Context.MODE_PRIVATE);
        biPrefs = ctx.getSharedPreferences("glass_bi", Context.MODE_PRIVATE);
        reload();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    loadOne(app, en, "en.txt", "en_bi.txt");
                    loadOne(app, ar, "ar.txt", "ar_bi.txt");
                } catch (Throwable ignored) { }
                ready = true;
            }
        }, "glass-dict");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void loadOne(Context ctx, WordIndex idx, String words, String bi) {
        try (InputStream w = ctx.getAssets().open(words)) {
            InputStream b = null;
            try { b = ctx.getAssets().open(bi); } catch (Exception ignored) { }
            try { idx.load(w, b); } finally { if (b != null) b.close(); }
        } catch (Exception ignored) { }
    }

    /** Re-read learned words (e.g. after an import from the settings screen). */
    public void reload() {
        learned.clear();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getValue() instanceof Integer) learned.put(e.getKey(), (Integer) e.getValue());
        }
        learnedBi.clear();
        for (Map.Entry<String, ?> e : biPrefs.getAll().entrySet()) {
            if (e.getValue() instanceof Integer) learnedBi.put(e.getKey(), (Integer) e.getValue());
        }
    }

    private static void put(Map<String, long[]> pool, String[] spelling, String word, long score, boolean arabic) {
        String k = WordIndex.norm(word, arabic);
        long[] cur = pool.get(k);
        if (cur == null) {
            pool.put(k, new long[]{score, pool.size()});
            spelling[pool.size() - 1] = word;
        } else if (score > cur[0]) {
            cur[0] = score;
            spelling[(int) cur[1]] = word;
        }
    }

    /** Best completions / corrections for what is being typed (never the typed word itself). */
    public List<String> complete(String prefix, boolean arabic, int max) {
        List<String> res = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return res;
        String pk = WordIndex.norm(prefix, arabic);
        Map<String, long[]> pool = new LinkedHashMap<>();
        String[] spelling = new String[256];
        for (Map.Entry<String, Integer> e : learned.entrySet()) {
            String k = e.getKey();
            if (isArabic(k) != arabic) continue;
            String nk = WordIndex.norm(k, arabic);
            if (nk.startsWith(pk) && nk.length() > pk.length() && pool.size() < 200) {
                put(pool, spelling, k, LEARN_BASE + e.getValue() * LEARN_STEP, arabic);
            }
        }
        if (ready) {
            WordIndex idx = arabic ? ar : en;
            for (WordIndex.Cand c : idx.complete(prefix, max + 2)) put(pool, spelling, c.word, c.score, arabic);
            for (WordIndex.Cand c : idx.corrections(prefix, 3)) put(pool, spelling, c.word, c.score * 6 / 10, arabic);
        }
        final List<long[]> order = new ArrayList<>(pool.values());
        Collections.sort(order, new Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) { return Long.compare(b[0], a[0]); }
        });
        boolean cap = !arabic && Character.isUpperCase(prefix.charAt(0));
        boolean allCaps = !arabic && prefix.length() > 1 && prefix.equals(prefix.toUpperCase(Locale.ROOT));
        for (long[] o : order) {
            if (res.size() >= max) break;
            String w = spelling[(int) o[1]];
            if (arabic ? w.equals(prefix) : w.equalsIgnoreCase(prefix)) continue;
            res.add(arabic ? w : styled(w, cap, allCaps));
        }
        return res;
    }

    private static String styled(String w, boolean cap, boolean allCaps) {
        if (allCaps) return w.toUpperCase(Locale.ROOT);
        if (cap) return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        if (w.equals("i")) return "I";
        if (w.startsWith("i'")) return "I" + w.substring(1);
        return w;
    }

    /** Likely next words after {@code prev} (empty prev = sentence start). */
    public List<String> next(String prev, boolean arabic, int max) {
        List<String> res = new ArrayList<>();
        if (prev != null && !prev.isEmpty()) {
            boolean ar2 = isArabic(prev);
            String pk = WordIndex.norm(prev, ar2);
            String pre = pk + SEP;
            List<Map.Entry<String, Integer>> hits = new ArrayList<>();
            for (Map.Entry<String, Integer> e : learnedBi.entrySet()) {
                if (e.getKey().startsWith(pre)) hits.add(e);
            }
            Collections.sort(hits, new Comparator<Map.Entry<String, Integer>>() {
                @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue() - a.getValue();
                }
            });
            for (Map.Entry<String, Integer> e : hits) {
                if (res.size() >= max) break;
                res.add(e.getKey().substring(pre.length()));
            }
            if (ready) {
                for (String w : (ar2 ? ar : en).next(prev)) {
                    if (res.size() >= max) break;
                    String s = ar2 ? w : styled(w, false, false);
                    if (!res.contains(s)) res.add(s);
                }
            }
        }
        for (String s : starters(arabic)) {
            if (res.size() >= max) break;
            if (!res.contains(s)) res.add(s);
        }
        return res;
    }

    /** Words offered when nothing has been typed yet. */
    public List<String> starters(boolean arabic) {
        List<String> l = new ArrayList<>();
        if (arabic) { l.add("\u0627\u0632\u064A\u0643"); l.add("\u0634\u0643\u0631\u0627"); l.add("\u062A\u0645\u0627\u0645"); }
        else { l.add("I"); l.add("The"); l.add("Thanks"); }
        return l;
    }

    /** One "word<TAB>count" per line. */
    public static String exportAll(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("glass_words", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (e.getValue() instanceof Integer) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** Merges an export back in (keeps the higher count). Returns number of words read. */
    public static int importAll(Context ctx, String data) {
        SharedPreferences p = ctx.getSharedPreferences("glass_words", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit();
        int n = 0;
        for (String line : data.split("\n")) {
            String[] t = line.split("\t");
            if (t.length != 2 || t[0].trim().isEmpty()) continue;
            try {
                int c = Integer.parseInt(t[1].trim());
                int old = p.getInt(t[0], 0);
                ed.putInt(t[0], Math.max(old, c));
                n++;
            } catch (NumberFormatException ignored) { }
        }
        ed.apply();
        return n;
    }

    public void learn(String word) {
        if (word == null || word.length() < 2) return;
        String w = word;
        if (!isArabic(w) && ready && Character.isUpperCase(w.charAt(0))
                && w.substring(1).equals(w.substring(1).toLowerCase(Locale.ROOT))) {
            String lw = w.toLowerCase(Locale.ROOT);
            if (en.known(lw)) w = lw;      // "Hello" at a sentence start is just "hello"
        }
        Integer n = learned.get(w);
        int v = n == null ? 1 : n + 1;
        learned.put(w, v);
        prefs.edit().putInt(w, v).apply();
    }

    /** Remembers that {@code word} was typed right after {@code prev}. */
    public void learnPair(String prev, String word) {
        if (prev == null || prev.isEmpty() || word == null || word.length() < 2) return;
        String w = word;
        if (!isArabic(w) && w.length() > 1 && Character.isUpperCase(w.charAt(0))
                && w.substring(1).equals(w.substring(1).toLowerCase(Locale.ROOT))) w = w.toLowerCase(Locale.ROOT);
        String key = WordIndex.norm(prev, isArabic(prev)) + SEP + w;
        Integer n = learnedBi.get(key);
        int v = n == null ? 1 : n + 1;
        learnedBi.put(key, v);
        SharedPreferences.Editor ed = biPrefs.edit();
        ed.putInt(key, v);
        if (learnedBi.size() > 8000) {            // forget pairs seen only once
            Iterator<Map.Entry<String, Integer>> it = learnedBi.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> e = it.next();
                if (e.getValue() <= 1) { ed.remove(e.getKey()); it.remove(); }
            }
        }
        ed.apply();
    }

    public static boolean isArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
        }
        return false;
    }
}
