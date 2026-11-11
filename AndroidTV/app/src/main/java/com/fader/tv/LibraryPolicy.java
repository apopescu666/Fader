package com.fader.tv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Library ordering and queue/resume policy, kept pure so it can be unit tested without a TV.
 */
public final class LibraryPolicy {
    private LibraryPolicy() { }

    /**
     * Numeric-aware ordering so "01", "2", "4a" sort naturally, case-insensitively,
     * which is the ordering most native TV players use.
     */
    public static final Comparator<String> NATURAL = (a, b) -> naturalCompare(a, b);

    public static int naturalCompare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0;
        int la = a.length(), lb = b.length();
        while (i < la && j < lb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            boolean na = Character.isDigit(ca), nb = Character.isDigit(cb);
            if (na && nb) {
                int si = i, sj = j;
                while (i < la && Character.isDigit(a.charAt(i))) i++;
                while (j < lb && Character.isDigit(b.charAt(j))) j++;
                String na0 = a.substring(si, i);
                String nb0 = b.substring(sj, j);
                int c = compareNumberText(na0, nb0);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++; j++;
            }
        }
        return Integer.compare(la - i, lb - j);
    }

    private static int compareNumberText(String a, String b) {
        int ai = 0, bi = 0;
        while (ai < a.length() && a.charAt(ai) == '0') ai++;
        while (bi < b.length() && b.charAt(bi) == '0') bi++;
        int aDigits = a.length() - ai, bDigits = b.length() - bi;
        if (aDigits != bDigits) return Integer.compare(aDigits, bDigits);
        for (int k = 0; k < aDigits; k++) {
            char ca = a.charAt(ai + k), cb = b.charAt(bi + k);
            if (ca != cb) return ca - cb;
        }
        return 0;
    }

    /**
     * Stable "play-all-in-this-folder" queue ordering used to build the right-hand playlist when a
     * folder is opened: folders and their audio descendants, natural order, audio before subfolders
     * at each level so the first playable track is reachable quickly.
     */
    public static List<String> folderQueue(List<String> audioPaths, List<String> subfolderPaths) {
        List<String> out = new ArrayList<>();
        List<String> audio = new ArrayList<>(audioPaths);
        audio.sort(NATURAL);
        out.addAll(audio);
        List<String> sub = new ArrayList<>(subfolderPaths);
        sub.sort(NATURAL);
        out.addAll(sub);
        return out;
    }

    /** Bounds so a very large share does not build an unbounded queue or index. */
    public static final int MAX_QUEUE = 250000;
    public static int cap(int want) { return want > MAX_QUEUE ? MAX_QUEUE : want; }

    /** Clamp an index into [0,size); 0 for empty. */
    public static int clampIndex(int index, int size) {
        if (size <= 0) return 0;
        if (index < 0) return 0;
        return index >= size ? size - 1 : index;
    }
}
