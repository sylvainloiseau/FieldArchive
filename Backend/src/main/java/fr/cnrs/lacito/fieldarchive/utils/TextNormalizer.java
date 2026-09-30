package fr.cnrs.lacito.fieldarchive.utils;

import java.text.Normalizer;
import java.util.*;

/**
 * Text normalization shared by the natural-language features (history search, entity
 * search, RiC-O digest search, built-in agent): lowercase, accents removed, split on
 * anything that is not a letter or a digit.
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    private static final Set<String> STOP_WORDS = Set.copyOf(Arrays.asList(
            // English
            "a", "an", "the", "of", "to", "in", "on", "for", "and", "or", "with", "by", "from", "is",
            "are", "was", "were", "be", "all", "every", "any", "that", "this", "these", "those", "which",
            "who", "whom", "what", "me", "my", "it", "its", "as", "at", "into", "their", "there", "please",
            // French
            "le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "et", "ou", "a", "au", "aux",
            "en", "dans", "sur", "pour", "par", "avec", "est", "sont", "qui", "que", "quel", "quelle",
            "quels", "quelles", "ce", "cet", "cette", "ces", "tous", "toutes", "tout", "son", "sa", "ses",
            "leur", "leurs", "moi", "mon", "ma", "mes", "s", "il", "elle", "y"
    ));

    /** Lowercase and remove diacritics, keeping every other character. */
    public static String fold(String s) {
        if (s == null) return "";
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    /** Folded tokens, stop words included. */
    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : fold(s).split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Folded tokens without stop words. */
    public static Set<String> contentWords(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : tokens(s)) {
            if (!STOP_WORDS.contains(t)) out.add(t);
        }
        return out;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /** Splits camelCase / snake_case local names into words: "hasOrHadDemographicGroup" → "has or had demographic group". */
    public static String splitLocalName(String localName) {
        if (localName == null) return "";
        return localName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ');
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    /** 1.0 for identical words, decreasing with the edit distance relative to the longer word. */
    public static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    /**
     * A folded copy of a text that remembers, for each folded character, the index of the
     * original character it comes from — so that a regex matched on the folded text can
     * extract the original, case- and accent-preserving substring.
     */
    public static final class Mapped {
        public final String original;
        public final String folded;
        private final int[] origStart;

        private Mapped(String original, String folded, int[] origStart) {
            this.original = original;
            this.folded = folded;
            this.origStart = origStart;
        }

        public static Mapped of(String original) {
            StringBuilder sb = new StringBuilder();
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < original.length(); i++) {
                String f = fold(String.valueOf(original.charAt(i)));
                for (int k = 0; k < f.length(); k++) {
                    sb.append(f.charAt(k));
                    idx.add(i);
                }
            }
            int[] arr = new int[idx.size() + 1];
            for (int i = 0; i < idx.size(); i++) arr[i] = idx.get(i);
            arr[idx.size()] = original.length();
            return new Mapped(original, sb.toString(), arr);
        }

        /** Original substring corresponding to folded[start, end). */
        public String original(int start, int end) {
            if (start >= end) return "";
            int os = origStart[start];
            int oe = end >= origStart.length - 1 ? original.length() : origStart[end - 1] + 1;
            return original.substring(os, oe);
        }
    }
}
