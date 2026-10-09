package fr.cnrs.lacito.fieldarchive.services.matching.profile;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** The profiles of one data source, by IRI. */
public final class EntityProfiles {

    private final String dataSource;
    private final Map<String, EntityProfile> byIri = new LinkedHashMap<>();

    public EntityProfiles(String dataSource) {
        this.dataSource = dataSource;
    }

    public String dataSource() {
        return dataSource;
    }

    public void add(EntityProfile p) {
        byIri.put(p.iri(), p);
    }

    public EntityProfile get(String iri) {
        return byIri.get(iri);
    }

    public Collection<EntityProfile> all() {
        return byIri.values();
    }

    public int size() {
        return byIri.size();
    }
}
