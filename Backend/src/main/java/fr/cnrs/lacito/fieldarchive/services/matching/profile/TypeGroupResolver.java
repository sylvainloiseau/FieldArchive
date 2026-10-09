package fr.cnrs.lacito.fieldarchive.services.matching.profile;

import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfiguration;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;

import java.util.*;
import java.util.function.Function;

/**
 * Decides which type groups an entity belongs to, and under which group (if any) two entities
 * are compared.
 *
 * <ul>
 *   <li>An entity belongs to every group whose types, expanded with their subclasses, intersect
 *       its own types.</li>
 *   <li>Its <em>primary</em> groups are the most specific of those: a {@code rico:Person} is in
 *       Person and Agent, its primary group is Person.</li>
 *   <li>Two entities are compared under the most specific shared group that is primary for at
 *       least one of them: Person vs Person → Person; Person vs a plain Agent → Agent; Person vs
 *       Organization → not compared, although both are agents.</li>
 * </ul>
 */
public final class TypeGroupResolver {

    private final MatchingConfiguration configuration;
    private final Map<TypeGroup, Set<String>> expandedTypes = new LinkedHashMap<>();
    private final Map<Set<String>, List<TypeGroup>> groupsCache = new HashMap<>();
    private final Map<Set<String>, List<TypeGroup>> primaryCache = new HashMap<>();

    /**
     * @param expandWithSubtypes a type → the type plus its transitive subclasses
     *                           ({@code OntologyService::expandWithSubtypes})
     */
    public TypeGroupResolver(MatchingConfiguration configuration, Function<String, Set<String>> expandWithSubtypes) {
        this.configuration = configuration;
        for (TypeGroup g : configuration.groups()) {
            Set<String> types = new LinkedHashSet<>();
            for (String t : g.equivalentTypes()) {
                if (configuration.defaults().includeSubclasses()) types.addAll(expandWithSubtypes.apply(t));
                else types.add(t);
            }
            expandedTypes.put(g, types);
        }
    }

    public Set<String> expandedTypes(TypeGroup g) {
        return expandedTypes.getOrDefault(g, Set.of());
    }

    /** Every group the types belong to, in configuration order. */
    public List<TypeGroup> groupsOf(Set<String> types) {
        if (types.isEmpty()) return List.of();
        return groupsCache.computeIfAbsent(Set.copyOf(types), ts -> {
            List<TypeGroup> out = new ArrayList<>();
            for (TypeGroup g : configuration.groups()) {
                Set<String> expanded = expandedTypes.get(g);
                for (String t : ts) {
                    if (expanded.contains(t)) {
                        out.add(g);
                        break;
                    }
                }
            }
            return out;
        });
    }

    /** The minimal groups of {@link #groupsOf} for the order "G1 ⊑ G2 iff expanded(G1) ⊆ expanded(G2)". */
    public List<TypeGroup> primaryGroups(Set<String> types) {
        if (types.isEmpty()) return List.of();
        return primaryCache.computeIfAbsent(Set.copyOf(types), ts -> {
            List<TypeGroup> groups = groupsOf(ts);
            List<TypeGroup> out = new ArrayList<>();
            for (TypeGroup g : groups) {
                boolean hasStrictlySmaller = false;
                for (TypeGroup h : groups) {
                    if (h != g && isStrictlyNarrower(h, g)) {
                        hasStrictlySmaller = true;
                        break;
                    }
                }
                if (!hasStrictlySmaller) out.add(g);
            }
            return out;
        });
    }

    /** The group under which two entities are compared, or empty when they are not comparable. */
    public Optional<TypeGroup> sharedGroup(Set<String> typesA, Set<String> typesB) {
        boolean untypedA = typesA.isEmpty(), untypedB = typesB.isEmpty();
        if (untypedA && untypedB) return Optional.empty();
        if (untypedA || untypedB) {
            if (!configuration.defaults().compareUntypedEntities()) return Optional.empty();
            return mostSpecific(primaryGroups(untypedA ? typesB : typesA));
        }
        List<TypeGroup> groupsB = groupsOf(typesB);
        Set<TypeGroup> primaries = new HashSet<>(primaryGroups(typesA));
        primaries.addAll(primaryGroups(typesB));
        List<TypeGroup> candidates = new ArrayList<>();
        for (TypeGroup g : groupsOf(typesA)) {
            if (groupsB.contains(g) && primaries.contains(g)) candidates.add(g);
        }
        return mostSpecific(candidates);
    }

    /** Fewest expanded types first, then configuration order. */
    public Optional<TypeGroup> mostSpecific(Collection<TypeGroup> groups) {
        return groups.stream().min(Comparator
                .comparingInt((TypeGroup g) -> expandedTypes(g).size())
                .thenComparingInt(TypeGroup::order));
    }

    private boolean isStrictlyNarrower(TypeGroup narrow, TypeGroup wide) {
        Set<String> n = expandedTypes(narrow), w = expandedTypes(wide);
        return w.containsAll(n) && w.size() > n.size();
    }
}
