package fr.cnrs.lacito.fieldarchive.services.nlquery;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import fr.cnrs.lacito.fieldarchive.dtos.DataSourceDto;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService;
import fr.cnrs.lacito.fieldarchive.services.InternalDataBookkeeping;
import fr.cnrs.lacito.fieldarchive.services.OntologyService;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How the project's data actually records things, for every namespace: per type, the
 * properties used, how many entities use them, the kind of value, and the recurring values.
 * It is the only way the properties of ontologies other than RiC-O become known to the
 * natural-language agents. Aggregates only: a literal value is shown only when it occurs at
 * least twice and is short, so names, notes and transcriptions never appear.
 */
@Service
public class UsageProfileService {

    private static final int MAX_TYPES = 30;
    private static final int MAX_PREDICATES = 25;
    private static final int MAX_VALUES = 8;
    private static final int MAX_LITERAL_LENGTH = 40;
    private static final int QUERY_SECONDS = 10;

    private final DataSourceService dsService;
    private final RdfEntityService rdfEntityService;
    private final RicoDigestService ricoDigest;
    private final OntologyService ontologyService;
    private final Prefixes prefixes;

    private final AtomicLong dataVersion = new AtomicLong();
    private volatile CachedProfile cached;

    public UsageProfileService(DataSourceService dsService, RdfEntityService rdfEntityService,
                               RicoDigestService ricoDigest, OntologyService ontologyService, Prefixes prefixes) {
        this.dsService = dsService;
        this.rdfEntityService = rdfEntityService;
        this.ricoDigest = ricoDigest;
        this.ontologyService = ontologyService;
        this.prefixes = prefixes;
    }

    @EventListener
    public void onDataChanged(ProjectDataChangedEvent event) {
        dataVersion.incrementAndGet();
    }

    // =========================
    //  Model
    // =========================

    public static final class ValueCount {
        public String value;       // literal text or IRI
        public boolean iri;
        public String label;       // for IRIs
        public long count;
    }

    public static final class PredicateUsage {
        public String predicate;
        public long subjects;
        public List<String> kinds = new ArrayList<>();         // e.g. "text", "xsd:date", "→ rico:DemographicGroup"
        public List<ValueCount> values = new ArrayList<>();
        public Set<String> sources = new TreeSet<>();           // "internal" or the external source short names
    }

    public static final class TypeUsage {
        public String type;
        public long count;
        public List<PredicateUsage> predicates = new ArrayList<>();
    }

    public static final class Profile {
        public List<TypeUsage> types = new ArrayList<>();
        public Set<String> dataPredicates = new TreeSet<>();
        public Map<String, String> prefixes = new LinkedHashMap<>();
    }

    private record CachedProfile(Repository repo, long version, Profile profile) {}

    // =========================
    //  Profile
    // =========================

    public Profile profile() {
        Repository repo = ProjectContext.getRepository();
        long version = dataVersion.get();
        CachedProfile c = cached;
        if (c != null && c.repo == repo && c.version == version) return c.profile;
        Profile p = compute();
        cached = new CachedProfile(repo, version, p);
        return p;
    }

    /** Data graphs: the internal and external data sources, never the metadata or ontology graphs. */
    private Map<String, String> dataGraphs() {
        Map<String, String> graphToSource = new LinkedHashMap<>();
        String internal = ProjectContext.getProjectName() + "_internal";
        for (DataSourceDto ds : dsService.listDataSources()) {
            if (ds.graphIri == null) continue;
            graphToSource.put(ds.graphIri, internal.equals(ds.shortName) ? "internal" : ds.shortName);
        }
        return graphToSource;
    }

    private static String values(Collection<String> iris) {
        StringBuilder sb = new StringBuilder();
        for (String i : iris) sb.append('<').append(i).append("> ");
        return sb.toString();
    }

    private static final String EXCLUDED_PREDICATES = "FILTER(?p != <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> && ?p != <"
            + InternalDataBookkeeping.DCTERMS_CREATED + "> && ?p != <" + InternalDataBookkeeping.DCTERMS_MODIFIED + ">) ";

    private List<BindingSet> select(RepositoryConnection conn, String sparql) {
        TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql);
        q.setMaxExecutionTime(QUERY_SECONDS);
        List<BindingSet> out = new ArrayList<>();
        try (TupleQueryResult r = q.evaluate()) {
            while (r.hasNext()) out.add(r.next());
        }
        return out;
    }

    private static long longOf(BindingSet bs, String name) {
        Value v = bs.getValue(name);
        return v instanceof Literal l ? l.longValue() : 0;
    }

    private Profile compute() {
        Profile profile = new Profile();
        Map<String, String> graphs = dataGraphs();
        if (graphs.isEmpty()) return profile;
        String g = "VALUES ?g { " + values(graphs.keySet()) + "} ";
        Set<String> vocabularyTypes = vocabularyTypes();
        Set<String> namespaces = new LinkedHashSet<>();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            for (BindingSet bs : select(conn, "SELECT DISTINCT ?p WHERE { " + g + "GRAPH ?g { ?s ?p ?o } }")) {
                profile.dataPredicates.add(bs.getValue("p").stringValue());
            }

            List<BindingSet> typeRows = select(conn, "SELECT ?type (COUNT(DISTINCT ?s) AS ?n) WHERE { " + g
                    + "GRAPH ?g { ?s a ?type } } GROUP BY ?type ORDER BY DESC(?n) LIMIT " + MAX_TYPES);
            for (BindingSet row : typeRows) {
                TypeUsage t = new TypeUsage();
                t.type = row.getValue("type").stringValue();
                t.count = longOf(row, "n");
                namespaces.add(Prefixes.namespaceOf(t.type));
                t.predicates = describeType(conn, g, graphs, t.type, null, vocabularyTypes, namespaces);
                profile.types.add(t);
            }
        }
        profile.dataPredicates.forEach(p -> namespaces.add(Prefixes.namespaceOf(p)));
        profile.prefixes = prefixes.forData(namespaces);
        return profile;
    }

    /** Usage of every predicate (or of one, when {@code onlyPredicate} is set) on the entities of a type. */
    private List<PredicateUsage> describeType(RepositoryConnection conn, String g, Map<String, String> graphs, String type,
                                              String onlyPredicate, Set<String> vocabularyTypes, Set<String> namespaces) {
        String subject = "?s a <" + type + "> . ";
        String predicateFilter = onlyPredicate != null ? "VALUES ?p { <" + onlyPredicate + "> } " : "";
        String base = subject + g + predicateFilter + "GRAPH ?g { ?s ?p ?o } " + EXCLUDED_PREDICATES;

        Map<String, PredicateUsage> byPredicate = new LinkedHashMap<>();
        for (BindingSet bs : select(conn, "SELECT ?p (COUNT(DISTINCT ?s) AS ?n) WHERE { " + base
                + "} GROUP BY ?p ORDER BY DESC(?n) LIMIT " + MAX_PREDICATES)) {
            PredicateUsage pu = new PredicateUsage();
            pu.predicate = bs.getValue("p").stringValue();
            pu.subjects = longOf(bs, "n");
            byPredicate.put(pu.predicate, pu);
            namespaces.add(Prefixes.namespaceOf(pu.predicate));
        }
        if (byPredicate.isEmpty()) return List.of();

        for (BindingSet bs : select(conn, "SELECT DISTINCT ?p ?g WHERE { " + base + "}")) {
            PredicateUsage pu = byPredicate.get(bs.getValue("p").stringValue());
            if (pu != null) pu.sources.add(graphs.getOrDefault(bs.getValue("g").stringValue(), "?"));
        }

        for (BindingSet bs : select(conn, "SELECT ?p ?k (COUNT(*) AS ?n) WHERE { " + base
                + "BIND(IF(isLiteral(?o), CONCAT(\"L|\", STR(DATATYPE(?o)), \"|\", LANG(?o)), \"I\") AS ?k) } GROUP BY ?p ?k ORDER BY DESC(?n)")) {
            PredicateUsage pu = byPredicate.get(bs.getValue("p").stringValue());
            if (pu == null) continue;
            String k = bs.getValue("k").stringValue();
            if (k.startsWith("L|")) {
                String[] parts = k.split("\\|", -1);
                String dt = parts.length > 1 ? parts[1] : "";
                String lang = parts.length > 2 ? parts[2] : "";
                String kind = !lang.isEmpty() ? "text@" + lang
                        : dt.endsWith("#string") || dt.isEmpty() ? "text" : Prefixes.curie(dt, prefixes.base());
                if (!pu.kinds.contains(kind)) pu.kinds.add(kind);
            }
        }

        for (BindingSet bs : select(conn, "SELECT ?p ?ot (COUNT(DISTINCT ?o) AS ?n) WHERE { " + base
                + "FILTER(isIRI(?o)) ?o a ?ot } GROUP BY ?p ?ot ORDER BY DESC(?n)")) {
            PredicateUsage pu = byPredicate.get(bs.getValue("p").stringValue());
            if (pu == null) continue;
            String ot = bs.getValue("ot").stringValue();
            namespaces.add(Prefixes.namespaceOf(ot));
            String kind = "→ " + Prefixes.curie(ot, prefixes.base());
            if (!pu.kinds.contains(kind) && pu.kinds.stream().filter(x -> x.startsWith("→")).count() < 3) pu.kinds.add(kind);
        }
        for (PredicateUsage pu : byPredicate.values()) if (pu.kinds.isEmpty()) pu.kinds.add("→ entity");

        // recurring values (at least twice)
        for (BindingSet bs : select(conn, "SELECT ?p ?o (COUNT(*) AS ?n) WHERE { " + base
                + "} GROUP BY ?p ?o HAVING (COUNT(*) >= 2) ORDER BY ?p DESC(?n) LIMIT 2000")) {
            PredicateUsage pu = byPredicate.get(bs.getValue("p").stringValue());
            if (pu == null || pu.values.size() >= MAX_VALUES) continue;
            addValue(pu, bs.getValue("o"), longOf(bs, "n"));
        }
        // vocabulary entries, even when used once
        if (!vocabularyTypes.isEmpty()) {
            for (BindingSet bs : select(conn, "SELECT ?p ?o (COUNT(*) AS ?n) WHERE { " + base
                    + "FILTER(isIRI(?o)) ?o a ?vt . VALUES ?vt { " + values(vocabularyTypes) + "} } GROUP BY ?p ?o ORDER BY ?p DESC(?n) LIMIT 2000")) {
                PredicateUsage pu = byPredicate.get(bs.getValue("p").stringValue());
                if (pu == null || pu.values.size() >= MAX_VALUES) continue;
                String o = bs.getValue("o").stringValue();
                if (pu.values.stream().anyMatch(v -> v.value.equals(o))) continue;
                addValue(pu, bs.getValue("o"), longOf(bs, "n"));
            }
        }
        return new ArrayList<>(byPredicate.values());
    }

    private void addValue(PredicateUsage pu, Value o, long count) {
        ValueCount vc = new ValueCount();
        vc.count = count;
        if (o instanceof IRI iri) {
            vc.iri = true;
            vc.value = iri.stringValue();
            vc.label = rdfEntityService.bestLabel(iri);
        } else if (o instanceof Literal lit) {
            if (lit.getLabel().length() >= MAX_LITERAL_LENGTH) return;
            vc.value = lit.getLabel();
        } else {
            return;
        }
        pu.values.add(vc);
    }

    /** RiC-O vocabulary classes (rico:Type and subclasses), configured main terminologies, skos:Concept. */
    private Set<String> vocabularyTypes() {
        Set<String> out = new LinkedHashSet<>(ricoDigest.typeSubclasses());
        out.add("http://www.w3.org/2004/02/skos/core#Concept");
        ontologyService.getConfiguredOntologies().forEach((ns, data) -> {
            Object section = data.get("mainTerminologies");
            if (section instanceof Map<?, ?> m && m.get("value") instanceof List<?> list) {
                String namespace = (ns.endsWith("#") || ns.endsWith("/")) ? ns : ns + "#";
                for (Object o : list) {
                    String s = String.valueOf(o);
                    out.add(s.contains("://") ? s : namespace + s);
                }
            }
        });
        return out;
    }

    // =========================
    //  Rendering
    // =========================

    public String render(Profile p, int maxTypes) {
        if (p.types.isEmpty()) return "(the project has no typed entities yet)\n";
        StringBuilder sb = new StringBuilder();
        for (TypeUsage t : p.types.subList(0, Math.min(maxTypes, p.types.size()))) {
            sb.append(Prefixes.curie(t.type, p.prefixes)).append(" (").append(t.count).append(")\n");
            for (PredicateUsage pu : t.predicates) renderPredicate(sb, pu, t.count, p.prefixes);
        }
        return sb.toString();
    }

    private void renderPredicate(StringBuilder sb, PredicateUsage pu, long typeCount, Map<String, String> pfx) {
        sb.append("  ").append(Prefixes.curie(pu.predicate, pfx)).append("  ").append(pu.subjects);
        if (typeCount > 0) sb.append(" (").append(Math.round(100.0 * pu.subjects / typeCount)).append("%)");
        sb.append("  ").append(String.join(" | ", pu.kinds));
        if (!pu.values.isEmpty()) {
            List<String> vs = new ArrayList<>();
            for (ValueCount v : pu.values) {
                vs.add(v.iri ? "\"" + (v.label == null ? Prefixes.localName(v.value) : v.label) + "\" <" + v.value + "> (" + v.count + ")"
                        : "\"" + v.value + "\" (" + v.count + ")");
            }
            sb.append(": ").append(String.join(", ", vs));
        }
        if (!pu.sources.contains("internal")) sb.append("   [external: ").append(String.join(", ", pu.sources)).append(']');
        sb.append('\n');
    }

    /**
     * The {@code find_usage} tool: how the data records a property, or a word. A word is turned
     * into candidate properties by predicate local names found in the data and by the RiC-O
     * digest (directly or through the range class) — never by reading another ontology.
     */
    public String findUsage(String textOrProperty, String typeFilter) {
        Profile p = profile();
        Map<String, String> pfx = p.prefixes;
        List<String> candidates = new ArrayList<>();
        String t = textOrProperty == null ? "" : textOrProperty.trim();
        String expanded = Prefixes.expand(t, pfx);
        if (expanded.contains("://") || expanded.startsWith("urn:")) {
            candidates.add(expanded);
        } else {
            Set<String> words = TextNormalizer.contentWords(t);
            for (String pred : p.dataPredicates) {
                Set<String> localWords = TextNormalizer.contentWords(TextNormalizer.splitLocalName(Prefixes.localName(pred)));
                for (String w : words) {
                    boolean match = localWords.stream().anyMatch(lw -> lw.equals(w) || (w.length() > 4 && TextNormalizer.similarity(lw, w) >= 0.8));
                    if (match && !candidates.contains(pred)) candidates.add(pred);
                }
            }
            for (String rico : ricoDigest.propertiesFor(t, 6)) if (!candidates.contains(rico)) candidates.add(rico);
        }
        if (candidates.isEmpty()) return "No property found for \"" + t + "\".";

        String typeIri = typeFilter == null || typeFilter.isBlank() ? null : Prefixes.expand(typeFilter, pfx);
        Map<String, String> graphs = dataGraphs();
        String g = "VALUES ?g { " + values(graphs.keySet()) + "} ";
        Set<String> vocabularyTypes = vocabularyTypes();
        StringBuilder sb = new StringBuilder();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            int shown = 0;
            for (String pred : candidates) {
                if (shown >= 6) break;
                shown++;
                if (!p.dataPredicates.contains(pred)) {
                    sb.append(Prefixes.curie(pred, pfx)).append(": not used in this project");
                    RicoDigestService.Entry e = ricoDigest.entry(pred);
                    if (e != null) sb.append(" (RiC-O: ").append(e.text.trim().replace("\n", " ")).append(")");
                    sb.append('\n');
                    continue;
                }
                List<String> types = new ArrayList<>();
                if (typeIri != null) {
                    types.add(typeIri);
                } else {
                    for (BindingSet bs : select(conn, "SELECT ?t (COUNT(DISTINCT ?s) AS ?n) WHERE { " + g
                            + "GRAPH ?g { ?s <" + pred + "> ?o } ?s a ?t } GROUP BY ?t ORDER BY DESC(?n) LIMIT 4")) {
                        types.add(bs.getValue("t").stringValue());
                    }
                }
                sb.append(Prefixes.curie(pred, pfx)).append(":\n");
                if (types.isEmpty()) sb.append("  used on untyped entities only\n");
                for (String type : types) {
                    long typeCount = 0;
                    for (BindingSet bs : select(conn, "SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE { ?s a <" + type + "> }")) typeCount = longOf(bs, "n");
                    List<PredicateUsage> usage = describeType(conn, g, graphs, type, pred, vocabularyTypes, new HashSet<>());
                    if (usage.isEmpty()) {
                        sb.append("  on ").append(Prefixes.curie(type, pfx)).append(": not used\n");
                        continue;
                    }
                    sb.append("  on ").append(Prefixes.curie(type, pfx)).append(" (").append(typeCount).append("):");
                    StringBuilder line = new StringBuilder();
                    renderPredicate(line, usage.get(0), typeCount, pfx);
                    sb.append(line.substring(line.indexOf(" ", 2)));
                }
            }
        }
        return sb.toString();
    }
}
