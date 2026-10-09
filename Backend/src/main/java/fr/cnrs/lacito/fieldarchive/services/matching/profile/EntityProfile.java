package fr.cnrs.lacito.fieldarchive.services.matching.profile;

import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConcept;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.ComparableValue;
import org.eclipse.rdf4j.model.Value;

import java.util.*;

/** An entity as seen in one data source: its types, label and values, read from that graph only. */
public final class EntityProfile {

    private final EntityNode node;
    private final Set<String> types = new LinkedHashSet<>();
    private final Map<String, List<Value>> valuesByPredicate = new LinkedHashMap<>();
    private String label;
    /** Resolved values per concept, filled lazily by {@link ValueResolver}. */
    final Map<MatchingConcept, List<ComparableValue>> resolved = new IdentityHashMap<>();

    public EntityProfile(EntityNode node) {
        this.node = node;
    }

    public EntityNode node() {
        return node;
    }

    public String iri() {
        return node.iri();
    }

    public Set<String> types() {
        return types;
    }

    public boolean isUntyped() {
        return types.isEmpty();
    }

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void addValue(String predicate, Value value) {
        List<Value> values = valuesByPredicate.computeIfAbsent(predicate, k -> new ArrayList<>());
        if (!values.contains(value)) values.add(value);
    }

    public List<Value> valuesOf(String predicate) {
        return valuesByPredicate.getOrDefault(predicate, List.of());
    }

    /** The values of every predicate of the concept, in configuration order, without duplicates. */
    public List<Value> valuesOf(MatchingConcept concept) {
        List<Value> out = new ArrayList<>();
        for (String p : concept.predicates()) {
            for (Value v : valuesOf(p)) if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    public boolean hasAnyValue() {
        return !valuesByPredicate.isEmpty();
    }

    /** Label if known, else the IRI. */
    public String displayName() {
        return label != null ? label : node.iri();
    }
}
