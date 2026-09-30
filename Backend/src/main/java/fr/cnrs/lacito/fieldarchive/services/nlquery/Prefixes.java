package fr.cnrs.lacito.fieldarchive.services.nlquery;

import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.services.OntologyService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Prefix ↔ namespace map used to write compact prompts and to read the model's answers.
 * Built from the configured ontology names in {@code configuration.json} (configuration,
 * not ontology content) plus a few standard vocabularies. Namespaces met in the data that
 * are neither get generated prefixes ({@code ns1}, {@code ns2}…) by {@link #forData}.
 */
@Component
public class Prefixes {

    private final Map<String, String> base = new LinkedHashMap<>(); // prefix -> namespace

    public Prefixes(OntologyService ontologyService) {
        base.put("rico", RdfNamespaces.RICO);
        base.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        base.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        base.put("xsd", "http://www.w3.org/2001/XMLSchema#");
        base.put("owl", "http://www.w3.org/2002/07/owl#");
        base.put("dcterms", "http://purl.org/dc/terms/");
        ontologyService.getConfiguredOntologies().forEach((ns, data) -> {
            Object name = data.get("name");
            if (name == null) return;
            String namespace = (ns.endsWith("#") || ns.endsWith("/")) ? ns : ns + "#";
            base.putIfAbsent(name.toString(), namespace);
        });
    }

    /** Standard + configured prefixes, plus generated ones for the given namespaces seen in data. */
    public Map<String, String> forData(Collection<String> dataNamespaces) {
        Map<String, String> out = new LinkedHashMap<>(base);
        int i = 1;
        for (String ns : dataNamespaces) {
            if (ns == null || ns.isBlank() || out.containsValue(ns)) continue;
            while (out.containsKey("ns" + i)) i++;
            out.put("ns" + i, ns);
        }
        return out;
    }

    public Map<String, String> base() {
        return Collections.unmodifiableMap(base);
    }

    public static String namespaceOf(String iri) {
        int i = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return i < 0 ? iri : iri.substring(0, i + 1);
    }

    public static String localName(String iri) {
        int i = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return i < 0 ? iri : iri.substring(i + 1);
    }

    /** Compact form when a prefix is known and the local name is simple, else {@code <iri>}. */
    public static String curie(String iri, Map<String, String> prefixes) {
        if (iri == null) return "";
        for (Map.Entry<String, String> e : prefixes.entrySet()) {
            if (iri.startsWith(e.getValue())) {
                String local = iri.substring(e.getValue().length());
                if (local.matches("[A-Za-z_][A-Za-z0-9_\\-]*")) return e.getKey() + ":" + local;
            }
        }
        return "<" + iri + ">";
    }

    public String curie(String iri) {
        return curie(iri, base);
    }

    /** Full IRI from a CURIE or an IRI (with or without angle brackets); the input unchanged if unknown. */
    public static String expand(String curieOrIri, Map<String, String> prefixes) {
        if (curieOrIri == null) return null;
        String s = curieOrIri.trim();
        if (s.startsWith("<") && s.endsWith(">")) return s.substring(1, s.length() - 1);
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("urn:")) return s;
        int idx = s.indexOf(':');
        if (idx > 0) {
            String ns = prefixes.get(s.substring(0, idx));
            if (ns != null) return ns + s.substring(idx + 1);
        }
        return s;
    }

    public String expand(String curieOrIri) {
        return expand(curieOrIri, base);
    }

    public static String declarations(Map<String, String> prefixes) {
        StringBuilder sb = new StringBuilder();
        prefixes.forEach((p, ns) -> sb.append("PREFIX ").append(p).append(": <").append(ns).append(">\n"));
        return sb.toString();
    }
}
