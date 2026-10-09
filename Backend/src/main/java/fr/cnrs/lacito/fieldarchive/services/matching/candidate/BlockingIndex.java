package fr.cnrs.lacito.fieldarchive.services.matching.candidate;

import fr.cnrs.lacito.fieldarchive.services.matching.config.ComparatorKind;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConcept;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.EntityProfile;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.ValueResolver;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.ComparableValue;
import fr.cnrs.lacito.fieldarchive.utils.StringSimilarity;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;

import java.util.*;

/**
 * Index of one side's profiles by blocking keys, so that a profile is only compared with the
 * profiles sharing at least one key. Keys come from the group's concepts flagged
 * {@code blocking}: name tokens (≥ 3 characters) and their first 4 characters, normalised
 * exact values, years. A profile without any key is compared with every profile (nothing is
 * lost silently).
 */
public final class BlockingIndex {

    private static final Set<ComparatorKind> KEYED = EnumSet.of(ComparatorKind.NAME, ComparatorKind.STRING,
            ComparatorKind.EXACT, ComparatorKind.DATE, ComparatorKind.AUTO);
    private static final int MAX_YEARS = 10;

    private final TypeGroup group;
    private final ValueResolver resolver;
    private final List<EntityProfile> all;
    private final List<EntityProfile> unkeyed = new ArrayList<>();
    private final Map<String, List<EntityProfile>> byKey = new HashMap<>();

    private BlockingIndex(TypeGroup group, ValueResolver resolver, List<EntityProfile> profiles) {
        this.group = group;
        this.resolver = resolver;
        this.all = profiles;
        for (EntityProfile p : profiles) {
            Set<String> keys = keysOf(p);
            if (keys.isEmpty()) unkeyed.add(p);
            for (String k : keys) byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
        }
    }

    public static boolean applies(TypeGroup group) {
        return group.concepts().stream().anyMatch(c -> c.blocking() && KEYED.contains(c.comparator()));
    }

    public static BlockingIndex build(TypeGroup group, ValueResolver resolver, List<EntityProfile> profiles) {
        return new BlockingIndex(group, resolver, profiles);
    }

    public Collection<EntityProfile> candidatesFor(EntityProfile p) {
        Set<String> keys = keysOf(p);
        if (keys.isEmpty()) return all;
        Set<EntityProfile> out = new LinkedHashSet<>();
        for (String k : keys) out.addAll(byKey.getOrDefault(k, List.of()));
        out.addAll(unkeyed);
        return out;
    }

    Set<String> keysOf(EntityProfile p) {
        Set<String> keys = new HashSet<>();
        for (MatchingConcept c : group.concepts()) {
            if (!c.blocking() || !KEYED.contains(c.comparator())) continue;
            for (ComparableValue v : resolver.resolve(p, c)) {
                if (c.comparator() == ComparatorKind.EXACT && v.text() != null) {
                    String n = StringSimilarity.normalize(v.text());
                    if (!n.isEmpty()) keys.add("e:" + n);
                } else if (v.date() != null) {
                    int from = v.date().start().getYear(), to = Math.min(v.date().end().getYear(), from + MAX_YEARS);
                    for (int y = from; y <= to; y++) keys.add("y:" + y);
                } else if (v.text() != null) {
                    for (String token : TextNormalizer.tokens(v.text())) {
                        if (token.length() < 3) continue;
                        keys.add("t:" + token);
                        keys.add("p:" + token.substring(0, Math.min(4, token.length())));
                    }
                }
            }
        }
        return keys;
    }
}
