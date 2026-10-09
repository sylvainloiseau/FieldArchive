package fr.cnrs.lacito.fieldarchive.utils;

import java.util.List;

/**
 * String similarity measures used by the data matching comparators. All scores are in [0, 1].
 */
public final class StringSimilarity {

    private StringSimilarity() {}

    /**
     * Normalised form compared by the matching: lowercase, accents removed, every run of
     * characters that are not letters or digits replaced by one space, trimmed.
     */
    public static String normalize(String s) {
        if (s == null) return "";
        return TextNormalizer.fold(s).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * Indel similarity {@code 1 − (|a|+|b|−2·LCS(a,b)) / (|a|+|b|)}, which is what
     * {@code fuzzywuzzy.fuzz.ratio} computes (divided by 100) when python-Levenshtein is
     * installed. The strings are compared as given: normalise them first if needed.
     * Two empty strings give 1.
     */
    public static double indelRatio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) return 1.0;
        int lcs = longestCommonSubsequence(a, b);
        return 1.0 - (double) (total - 2 * lcs) / total;
    }

    /** {@link #indelRatio} after sorting the normalised tokens: "Dupont, Jean" = "Jean Dupont". */
    public static double tokenSortRatio(String a, String b) {
        return indelRatio(sortedTokens(a), sortedTokens(b));
    }

    static String sortedTokens(String s) {
        List<String> tokens = TextNormalizer.tokens(s);
        tokens.sort(String::compareTo);
        return String.join(" ", tokens);
    }

    static int longestCommonSubsequence(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                current[j] = ca == b.charAt(j - 1)
                        ? previous[j - 1] + 1
                        : Math.max(previous[j], current[j - 1]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
