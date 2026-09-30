package fr.cnrs.lacito.fieldarchive.services.nlquery;

import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * A condensed, searchable digest of the RiC-O ontology, the only ontology the
 * natural-language agents read (the other ontologies are known to them only through how the
 * project data uses them, see {@link UsageProfileService}). Built once, lazily, from
 * {@code rico.rdf}; its text is byte-identical from one call to the next, so it can sit in
 * the cached part of the prompt.
 */
@Service
public class RicoDigestService {

    private static final String RICO_FILE = "ontologies/rico.rdf";
    private final boolean withScopeNotes;
    private volatile Digest digest;

    public RicoDigestService(@org.springframework.beans.factory.annotation.Value("${fieldarchive.nlquery.rico-scope-notes:true}") boolean withScopeNotes) {
        this.withScopeNotes = withScopeNotes;
    }

    public enum Kind { CLASS, OBJECT_PROPERTY, DATATYPE_PROPERTY }

    public static final class Entry {
        public String iri;
        public String curie;
        public Kind kind;
        public String labelEn;
        public String labelFr;
        public List<String> allLabels = new ArrayList<>();
        public List<String> supers = new ArrayList<>();
        public List<String> domains = new ArrayList<>();
        public List<String> ranges = new ArrayList<>();
        public String inverse;
        public boolean functional;
        public String definition;
        public String scopeNote;
        public String text;                 // the rendered digest entry
        Set<String> labelWords = new HashSet<>();
        Set<String> definitionWords = new HashSet<>();
        Set<String> scopeWords = new HashSet<>();
    }

    private static final class Digest {
        String text;
        Map<String, Entry> byIri = new LinkedHashMap<>();
        Set<String> typeSubclasses = new HashSet<>();   // rico:Type and its transitive subclasses
    }

    private Digest digest() {
        Digest d = digest;
        if (d == null) {
            synchronized (this) {
                if (digest == null) {
                    digest = build();
                    System.out.println("RiC-O digest: " + digest.byIri.size() + " terms, "
                            + digest.text.length() + " characters (~" + digest.text.length() / 4 + " tokens)");
                }
                d = digest;
            }
        }
        return d;
    }

    /** The full digest, in a fixed order (classes by hierarchy then name, then properties by name). */
    public String text() {
        return digest().text;
    }

    public Entry entry(String iri) {
        return digest().byIri.get(iri);
    }

    public Collection<Entry> entries() {
        return digest().byIri.values();
    }

    /** rico:Type and all its subclasses: the RiC-O vocabulary ("terminology") classes. */
    public Set<String> typeSubclasses() {
        return digest().typeSubclasses;
    }

    /** Entries matching a text: labels (every language) weigh most, then definitions, then scope notes. */
    public List<Entry> search(String text, int limit) {
        Map<Entry, Double> scores = scoreAll(text);
        List<Entry> out = new ArrayList<>(scores.keySet());
        out.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        return out.subList(0, Math.min(limit, out.size()));
    }

    /**
     * RiC-O properties relevant to a word, directly or through their range class: "gender" gives
     * {@code rico:hasOrHadDemographicGroup}, whose range {@code rico:DemographicGroup} is defined
     * with "gender, (biological) sex".
     */
    public List<String> propertiesFor(String text, int limit) {
        Map<Entry, Double> scores = scoreAll(text);
        Map<String, Double> props = new HashMap<>();
        for (Map.Entry<Entry, Double> e : scores.entrySet()) {
            Entry en = e.getKey();
            if (en.kind != Kind.CLASS) {
                props.merge(en.iri, e.getValue(), Math::max);
            } else {
                for (Entry p : digest().byIri.values()) {
                    if (p.kind == Kind.OBJECT_PROPERTY && p.ranges.contains(en.iri)) {
                        props.merge(p.iri, e.getValue() * 0.9, Math::max);
                    }
                }
            }
        }
        List<String> out = new ArrayList<>(props.keySet());
        out.sort((a, b) -> Double.compare(props.get(b), props.get(a)));
        return out.subList(0, Math.min(limit, out.size()));
    }

    private Map<Entry, Double> scoreAll(String text) {
        Set<String> words = TextNormalizer.contentWords(text);
        Map<Entry, Double> scores = new HashMap<>();
        if (words.isEmpty()) return scores;
        for (Entry e : digest().byIri.values()) {
            double s = 0;
            for (String w : words) {
                if (e.labelWords.contains(w)) s += 3;
                else if (e.definitionWords.contains(w)) s += 1;
                else if (e.scopeWords.contains(w)) s += 0.5;
            }
            if (s > 0) scores.put(e, s);
        }
        return scores;
    }

    // =========================
    //  Building
    // =========================

    private Digest build() {
        Model model;
        try (InputStream is = new ClassPathResource(RICO_FILE).getInputStream()) {
            model = Rio.parse(is, "", RDFFormat.RDFXML);
        } catch (Exception e) {
            System.err.println("WARNING: cannot read " + RICO_FILE + " for the RiC-O digest: " + e.getMessage());
            Digest empty = new Digest();
            empty.text = "(RiC-O digest unavailable)";
            return empty;
        }

        Digest d = new Digest();
        Map<String, String> prefixes = Map.of("rico", RdfNamespaces.RICO,
                "xsd", "http://www.w3.org/2001/XMLSchema#", "rdfs", "http://www.w3.org/2000/01/rdf-schema#");

        List<Entry> classes = new ArrayList<>();
        List<Entry> properties = new ArrayList<>();
        for (Resource r : model.filter(null, RDF.TYPE, OWL.CLASS).subjects()) {
            if (r instanceof IRI iri && iri.stringValue().startsWith(RdfNamespaces.RICO)) classes.add(entry(model, iri, Kind.CLASS, prefixes));
        }
        for (Resource r : model.filter(null, RDF.TYPE, OWL.OBJECTPROPERTY).subjects()) {
            if (r instanceof IRI iri && iri.stringValue().startsWith(RdfNamespaces.RICO)) properties.add(entry(model, iri, Kind.OBJECT_PROPERTY, prefixes));
        }
        for (Resource r : model.filter(null, RDF.TYPE, OWL.DATATYPEPROPERTY).subjects()) {
            if (r instanceof IRI iri && iri.stringValue().startsWith(RdfNamespaces.RICO)) properties.add(entry(model, iri, Kind.DATATYPE_PROPERTY, prefixes));
        }

        // classes: depth in the hierarchy, then name; properties: by name — a fixed order keeps the text cacheable
        Map<String, Entry> classByIri = new HashMap<>();
        classes.forEach(c -> classByIri.put(c.iri, c));
        classes.sort(Comparator.comparingInt((Entry c) -> depth(c, classByIri, new HashSet<>())).thenComparing(c -> c.curie));
        properties.sort(Comparator.comparing(p -> p.curie));

        // rico:Type and its subclasses
        String typeIri = RdfNamespaces.RICO + "Type";
        d.typeSubclasses.add(typeIri);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Entry c : classes) {
                if (!d.typeSubclasses.contains(c.iri) && c.supers.stream().anyMatch(d.typeSubclasses::contains)) {
                    d.typeSubclasses.add(c.iri);
                    grew = true;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("RiC-O (Records in Contexts ontology), prefix rico: <").append(RdfNamespaces.RICO).append(">\n");
        sb.append("Classes (").append(classes.size()).append("):\n");
        for (Entry c : classes) { sb.append(c.text); d.byIri.put(c.iri, c); }
        sb.append("\nProperties (").append(properties.size()).append("):\n");
        for (Entry p : properties) { sb.append(p.text); d.byIri.put(p.iri, p); }
        d.text = sb.toString();
        return d;
    }

    private static int depth(Entry c, Map<String, Entry> byIri, Set<String> seen) {
        if (!seen.add(c.iri)) return 0;
        int best = 0;
        for (String s : c.supers) {
            Entry sup = byIri.get(s);
            if (sup != null) best = Math.max(best, 1 + depth(sup, byIri, seen));
        }
        return best;
    }

    private Entry entry(Model model, IRI iri, Kind kind, Map<String, String> prefixes) {
        Entry e = new Entry();
        e.iri = iri.stringValue();
        e.curie = Prefixes.curie(e.iri, prefixes);
        e.kind = kind;

        for (Value v : model.filter(iri, RDFS.LABEL, null).objects()) {
            if (!(v instanceof Literal lit)) continue;
            String lang = lit.getLanguage().orElse("");
            e.allLabels.add(lit.getLabel());
            if (lang.equals("en") || (lang.isEmpty() && e.labelEn == null)) e.labelEn = lit.getLabel();
            if (lang.equals("fr")) e.labelFr = lit.getLabel();
        }
        e.definition = joinEnglish(model, iri, List.of(RDFS.COMMENT, SKOS.DEFINITION));
        e.scopeNote = joinEnglish(model, iri, List.of(SKOS.SCOPE_NOTE));

        IRI superPredicate = kind == Kind.CLASS ? RDFS.SUBCLASSOF : RDFS.SUBPROPERTYOF;
        for (Value v : model.filter(iri, superPredicate, null).objects()) {
            if (v instanceof IRI s && s.stringValue().startsWith(RdfNamespaces.RICO)) e.supers.add(s.stringValue());
        }
        if (kind != Kind.CLASS) {
            e.domains = classesOf(model, iri, RDFS.DOMAIN);
            e.ranges = classesOf(model, iri, RDFS.RANGE);
            for (Value v : model.filter(iri, OWL.INVERSEOF, null).objects()) if (v instanceof IRI i) e.inverse = i.stringValue();
            if (e.inverse == null) {
                for (Resource r : model.filter(null, OWL.INVERSEOF, iri).subjects()) if (r instanceof IRI i) e.inverse = i.stringValue();
            }
            e.functional = model.contains(iri, RDF.TYPE, OWL.FUNCTIONALPROPERTY);
        }
        Collections.sort(e.supers);

        for (String l : e.allLabels) e.labelWords.addAll(TextNormalizer.contentWords(l));
        e.labelWords.addAll(TextNormalizer.contentWords(TextNormalizer.splitLocalName(Prefixes.localName(e.iri))));
        if (e.definition != null) e.definitionWords.addAll(TextNormalizer.contentWords(e.definition));
        if (e.scopeNote != null) e.scopeWords.addAll(TextNormalizer.contentWords(e.scopeNote));

        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(e.curie).append("  (").append(switch (kind) {
            case CLASS -> "class";
            case OBJECT_PROPERTY -> "object property";
            case DATATYPE_PROPERTY -> "datatype property";
        }).append(")");
        if (e.labelEn != null) sb.append("  label: ").append(e.labelEn);
        if (e.labelFr != null) sb.append(" / ").append(e.labelFr);
        if (!e.supers.isEmpty()) sb.append("  sub of: ").append(curies(e.supers, prefixes));
        sb.append('\n');
        if (kind != Kind.CLASS) {
            sb.append("    ").append(e.domains.isEmpty() ? "(any)" : "(" + curies(e.domains, prefixes).replace(", ", " | ") + ")")
                    .append(" → ").append(e.ranges.isEmpty() ? "(any)" : curies(e.ranges, prefixes).replace(", ", " | "));
            if (e.inverse != null) sb.append("   inverse: ").append(Prefixes.curie(e.inverse, prefixes));
            if (e.functional) sb.append("   [at most one value]");
            sb.append('\n');
        }
        if (e.definition != null) sb.append("    def: ").append(e.definition).append('\n');
        if (withScopeNotes && e.scopeNote != null) sb.append("    scope: ").append(e.scopeNote).append('\n');
        e.text = sb.toString();
        return e;
    }

    private static String curies(List<String> iris, Map<String, String> prefixes) {
        List<String> out = new ArrayList<>();
        for (String i : iris) out.add(Prefixes.curie(i, prefixes));
        return String.join(", ", out);
    }

    private static String joinEnglish(Model model, IRI iri, List<IRI> predicates) {
        List<String> parts = new ArrayList<>();
        for (IRI p : predicates) {
            for (Value v : model.filter(iri, p, null).objects()) {
                if (v instanceof Literal lit) {
                    String lang = lit.getLanguage().orElse("");
                    if (lang.isEmpty() || lang.equals("en")) parts.add(lit.getLabel().replaceAll("\\s+", " ").trim());
                } else if (v instanceof Resource r) {
                    // skos notes may be structured: rdf:value inside a blank node
                    for (Value inner : model.filter(r, RDF.VALUE, null).objects()) {
                        if (inner instanceof Literal lit && lit.getLanguage().map(l -> l.equals("en")).orElse(true)) {
                            parts.add(lit.getLabel().replaceAll("\\s+", " ").trim());
                        }
                    }
                }
            }
        }
        return parts.isEmpty() ? null : String.join(" ", new LinkedHashSet<>(parts));
    }

    /** Named classes of a domain / range, flattening owl:unionOf. */
    private static List<String> classesOf(Model model, IRI property, IRI predicate) {
        Set<String> out = new TreeSet<>();
        for (Value v : model.filter(property, predicate, null).objects()) {
            if (v instanceof IRI i) {
                out.add(i.stringValue());
            } else if (v instanceof Resource node) {
                for (Value list : model.filter(node, OWL.UNIONOF, null).objects()) {
                    Resource cur = list instanceof Resource r ? r : null;
                    int guard = 0;
                    while (cur != null && !RDF.NIL.equals(cur) && guard++ < 100) {
                        for (Value first : model.filter(cur, RDF.FIRST, null).objects()) {
                            if (first instanceof IRI fi) out.add(fi.stringValue());
                        }
                        Resource next = null;
                        for (Value rest : model.filter(cur, RDF.REST, null).objects()) {
                            if (rest instanceof Resource rr) next = rr;
                        }
                        cur = next;
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }
}
