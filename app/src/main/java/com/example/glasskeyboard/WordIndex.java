package com.example.glasskeyboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Pure-Java word list: prefix completion, typo correction and next-word table (no Android classes). */
final class WordIndex {

    static final class Cand {
        final String word;
        final long score;
        Cand(String word, long score) { this.word = word; this.score = score; }
    }

    private static final class Entry {
        String key, word; int freq;
    }

    final boolean arabic;
    private String[] words = new String[0];
    private String[] keys = new String[0];
    private int[] freq = new int[0];
    private final HashMap<String, Integer> exact = new HashMap<>();
    private final HashMap<String, String[]> bi = new HashMap<>();
    private final HashMap<String, Integer> apos = new HashMap<>();      // "dont" -> index of "don't"
    private static final HashMap<String, String> CHAT = new HashMap<>();
    static {
        CHAT.put("thx", "thanks"); CHAT.put("pls", "please"); CHAT.put("plz", "please");
        CHAT.put("tmrw", "tomorrow"); CHAT.put("tmr", "tomorrow"); CHAT.put("bc", "because");
        CHAT.put("bcuz", "because"); CHAT.put("abt", "about"); CHAT.put("u", "you");
        CHAT.put("r", "are"); CHAT.put("gonna", "going"); CHAT.put("msg", "message");
    }

    private static final String AR_LETTERS =
            "\u0627\u0628\u062A\u062B\u062C\u062D\u062E\u062F\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0639\u063A\u0641\u0642\u0643\u0644\u0645\u0646\u0647\u0648\u064A\u0621";
    private static final String EN_LETTERS = "abcdefghijklmnopqrstuvwxyz'";

    WordIndex(boolean arabic) { this.arabic = arabic; }

    /** Normalised form used for matching: lower case (English) or without marks / hamza / ta-marbuta variants (Arabic). */
    static String norm(String s, boolean arabic) {
        if (!arabic) return s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x064B && c <= 0x0652) || c == 0x0670 || c == 0x0640) continue;
            switch (c) {
                case '\u0623': case '\u0625': case '\u0622': case '\u0671': c = '\u0627'; break;
                case '\u0649': case '\u0626': c = '\u064A'; break;
                case '\u0624': c = '\u0648'; break;
                case '\u0629': c = '\u0647'; break;
                default: break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    void load(InputStream wordsIn, InputStream biIn) throws IOException {
        List<Entry> list = new ArrayList<>(50000);
        BufferedReader br = new BufferedReader(new InputStreamReader(wordsIn, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            Entry e = new Entry();
            e.word = line.substring(0, t);
            try { e.freq = Integer.parseInt(line.substring(t + 1).trim()); } catch (NumberFormatException ex) { continue; }
            e.key = norm(e.word, arabic);
            if (e.key.isEmpty()) continue;
            list.add(e);
        }
        br.close();
        Entry[] arr = list.toArray(new Entry[0]);
        Arrays.sort(arr, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.key.compareTo(b.key); }
        });
        String[] w = new String[arr.length], k = new String[arr.length];
        int[] f = new int[arr.length];
        HashMap<String, Integer> ex = new HashMap<>();
        for (int i = 0; i < arr.length; i++) {
            w[i] = arr[i].word; k[i] = arr[i].key; f[i] = arr[i].freq;
            Integer old = ex.get(k[i]);
            if (old == null || f[old] < f[i]) ex.put(k[i], i);
        }
        words = w; keys = k; freq = f;
        exact.clear(); exact.putAll(ex);
        apos.clear();
        if (!arabic) {
            for (int i = 0; i < k.length; i++) {
                if (k[i].indexOf('\'') < 0) continue;
                String stripped = k[i].replace("'", "");
                Integer old = apos.get(stripped);
                if (old == null || f[old] < f[i]) apos.put(stripped, i);
            }
        }
        if (biIn != null) {
            BufferedReader bb = new BufferedReader(new InputStreamReader(biIn, "UTF-8"));
            while ((line = bb.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 2) continue;
                bi.put(norm(t[0], arabic), Arrays.copyOfRange(t, 1, t.length));
            }
            bb.close();
        }
    }

    boolean known(String word) { return exact.containsKey(norm(word, arabic)); }

    /** True when this exact spelling is in the list. */
    boolean hasWord(String word) {
        String k = norm(word, arabic);
        for (int i = lowerBound(k); i < keys.length && keys[i].equals(k); i++) {
            if (words[i].equals(word)) return true;
        }
        return false;
    }

    private int lowerBound(String p) {
        int lo = 0, hi = keys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid].compareTo(p) < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Most frequent words that start with the prefix (the prefix itself is skipped). */
    List<Cand> complete(String prefix, int max) {
        List<Cand> out = new ArrayList<>();
        String pk = norm(prefix, arabic);
        if (pk.isEmpty() || keys.length == 0) return out;
        int lo = lowerBound(pk);
        boolean typedIsWord = hasWord(prefix);
        int n = Math.max(1, max);
        int[] bestIdx = new int[n];
        Arrays.fill(bestIdx, -1);
        for (int i = lo; i < keys.length && keys[i].startsWith(pk); i++) {
            if (keys[i].length() == pk.length() && (typedIsWord || words[i].equals(prefix))) continue;
            for (int s = 0; s < n; s++) {
                if (bestIdx[s] < 0 || freq[i] > freq[bestIdx[s]]) {
                    for (int t = n - 1; t > s; t--) bestIdx[t] = bestIdx[t - 1];
                    bestIdx[s] = i;
                    break;
                }
            }
        }
        for (int s = 0; s < n; s++) if (bestIdx[s] >= 0) out.add(new Cand(words[bestIdx[s]], freq[bestIdx[s]]));
        return out;
    }

    /** Words one edit away (delete / swap / replace / insert) from an unknown word. */
    List<Cand> corrections(String word, int max) {
        List<Cand> out = new ArrayList<>();
        String k = norm(word, arabic);
        if (!arabic && keys.length > 0) {
            Integer ci = apos.get(k);                       // dont -> don't, im -> i'm
            if (ci != null) {
                Integer me = exact.get(k);
                if (me == null || (long) freq[ci] * 6 >= freq[me]) out.add(new Cand(words[ci], (long) freq[ci] * 5));
            }
            String chat = CHAT.get(k);                      // thx -> thanks
            if (chat != null) {
                Integer ti = exact.get(chat);
                out.add(new Cand(chat, ti == null ? 1_000_000L : (long) freq[ti] * 5));
            }
        }
        if (k.length() < 3 || keys.length == 0) return out;
        // a known word is only "corrected" when a neighbour is far more common (teh -> the)
        Integer self = exact.get(k);
        long minScore = self == null ? 0 : 40L * freq[self];
        String alpha = arabic ? AR_LETTERS : EN_LETTERS;
        HashSet<String> seen = new HashSet<>();
        List<String> edits = new ArrayList<>();
        int n = k.length();
        for (int i = 0; i < n; i++) edits.add(k.substring(0, i) + k.substring(i + 1));
        for (int i = 0; i + 1 < n; i++)
            edits.add(k.substring(0, i) + k.charAt(i + 1) + k.charAt(i) + k.substring(i + 2));
        for (int i = 0; i < n; i++)
            for (int a = 0; a < alpha.length(); a++)
                edits.add(k.substring(0, i) + alpha.charAt(a) + k.substring(i + 1));
        for (int i = 0; i <= n; i++)
            for (int a = 0; a < alpha.length(); a++)
                edits.add(k.substring(0, i) + alpha.charAt(a) + k.substring(i));
        for (String e : edits) {
            if (e.length() < 2 || e.equals(k) || !seen.add(e)) continue;
            Integer ix = exact.get(e);
            if (ix == null || freq[ix] <= minScore) continue;
            out.add(new Cand(words[ix], freq[ix]));
        }
        java.util.Collections.sort(out, new Comparator<Cand>() {
            @Override public int compare(Cand a, Cand b) { return Long.compare(b.score, a.score); }
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Words that usually follow the given word (best first); empty array when unknown. */
    String[] next(String prev) {
        String[] r = bi.get(norm(prev, arabic));
        return r == null ? new String[0] : r;
    }
}
