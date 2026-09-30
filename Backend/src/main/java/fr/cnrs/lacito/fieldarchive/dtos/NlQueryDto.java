package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

/** Answer of POST /nlquery/translate. */
public class NlQueryDto {
    public String queryType;             // SELECT | UPDATE | CLARIFY | UNSUPPORTED
    public String sparql;                // placeholders already replaced; null for CLARIFY / UNSUPPORTED
    public String explanation;
    public String targetEntityIri;
    public List<String> createdEntityIris = new ArrayList<>();
    public String clarifyKind;           // ENTITY | ENCODING (CLARIFY only)
    public List<EntityMatchDto> candidates = new ArrayList<>();
    public List<EncodingOptionDto> encodingOptions = new ArrayList<>();
    public List<String> lookups = new ArrayList<>();
    public List<String> examples = new ArrayList<>();   // UNSUPPORTED only
    public String provider;
    public String model;
}
