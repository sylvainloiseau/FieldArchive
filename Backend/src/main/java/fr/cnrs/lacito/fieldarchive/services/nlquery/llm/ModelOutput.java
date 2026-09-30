package fr.cnrs.lacito.fieldarchive.services.nlquery.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * The final answer of an agent, parsed from JSON that follows {@code nlquery/output-schema.json}.
 * Every field is present; the ones that don't apply are empty strings or empty lists.
 */
public class ModelOutput {
    public String queryType;        // SELECT | UPDATE | CLARIFY (UNSUPPORTED: built-in agent only)
    public String sparql = "";
    public String explanation = "";
    public List<NewEntity> newEntities = new ArrayList<>();
    public String targetEntityIri = "";
    public String clarifyKind = "NONE"; // ENTITY | ENCODING | NONE
    public List<String> candidateIris = new ArrayList<>();
    public List<EncodingOption> encodingOptions = new ArrayList<>();

    public static class NewEntity {
        public String placeholder;
        public String typeIri;
        public String name = "";
    }

    public static class EncodingOption {
        public String id;
        public String label;
        public String description;
        public String basis;          // project data | configuration | RiC-O
        public String exampleTriple;
        public String statement;
    }
}
