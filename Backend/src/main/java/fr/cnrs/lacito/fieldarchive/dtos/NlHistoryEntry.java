package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

/** One entry of natural_language_query_history.json. */
public class NlHistoryEntry {
    public String id;
    public String timestamp;
    public String question;
    public List<NlClarification> clarifications = new ArrayList<>();
    public String queryType;
    public String sparql;
    public String generatedSparql;
    public boolean edited;
    public List<EntityRef> entities = new ArrayList<>();
    public String targetEntityIri;
    public List<String> createdEntityIris = new ArrayList<>();
    public Integer resultCount;
    public int useCount = 1;
    public String provider;
    public String model;

    public static class EntityRef {
        public String iri;
        public String label;

        public EntityRef() {}
        public EntityRef(String iri, String label) { this.iri = iri; this.label = label; }
    }
}
