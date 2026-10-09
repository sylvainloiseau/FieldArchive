package fr.cnrs.lacito.fieldarchive.services.matching.cluster;

/** An unordered pair of two distinct data sources, stored in canonical order (shortNames sorted). */
public record DataSourcePair(String first, String second) {

    public static final String SEPARATOR = "⇄";

    public DataSourcePair {
        if (first == null || second == null || first.equals(second)) {
            throw new IllegalArgumentException("A data-source pair needs two different sources.");
        }
        if (first.compareTo(second) > 0) {
            String swap = first;
            first = second;
            second = swap;
        }
    }

    public static DataSourcePair of(String a, String b) {
        return new DataSourcePair(a, b);
    }

    public static DataSourcePair fromKey(String key) {
        int i = key.indexOf(SEPARATOR);
        if (i < 0) throw new IllegalArgumentException("Not a pair key: " + key);
        return new DataSourcePair(key.substring(0, i), key.substring(i + SEPARATOR.length()));
    }

    public String key() {
        return first + SEPARATOR + second;
    }

    public boolean contains(String dataSource) {
        return first.equals(dataSource) || second.equals(dataSource);
    }

    public String other(String dataSource) {
        return first.equals(dataSource) ? second : first;
    }
}
