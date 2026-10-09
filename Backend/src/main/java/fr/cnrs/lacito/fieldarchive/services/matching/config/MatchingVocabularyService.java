package fr.cnrs.lacito.fieldarchive.services.matching.config;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import fr.cnrs.lacito.fieldarchive.dtos.OntologyClassDto;
import fr.cnrs.lacito.fieldarchive.dtos.OntologyPropertyDto;
import fr.cnrs.lacito.fieldarchive.dtos.OntologySchemaDto;
import fr.cnrs.lacito.fieldarchive.dtos.PropertyKind;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConceptDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.OntologyService;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.services.matching.DataSourceCatalog;
import fr.cnrs.lacito.fieldarchive.services.matching.MatchStore;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * What the matching settings page offers: the types and predicates known to the ontologies or
 * used in the data (with usage counts per source), the coverage of a group's concepts, and the
 * data facts used for the validation warnings.
 */
@Service
public class MatchingVocabularyService {

    private static final int MAX_ROWS = 200_000;
    private static final Set<String> DATE_TYPES = Set.of(XSD.DATE.stringValue(), XSD.DATETIME.stringValue(),
            XSD.GYEAR.stringValue(), XSD.GYEARMONTH.stringValue());
    private static final Set<String> NUMBER_TYPES = Set.of(XSD.INTEGER.stringValue(), XSD.INT.stringValue(),
            XSD.LONG.stringValue(), XSD.DECIMAL.stringValue(), XSD.DOUBLE.stringValue(), XSD.FLOAT.stringValue(),
            XSD.NON_NEGATIVE_INTEGER.stringValue(), XSD.POSITIVE_INTEGER.stringValue(), XSD.SHORT.stringValue());

    private final OntologyService ontologyService;
    private final DataSourceCatalog catalog;
    private final MatchStore store;
    private final MatchingConfigurationService configurationService;

    private Set<String> usedTypesCache;
    private Set<String> usedPredicatesCache;
    private String cacheProject;

    public MatchingVocabularyService(OntologyService ontologyService, DataSourceCatalog catalog, MatchStore store,
                                     MatchingConfigurationService configurationService) {
        this.ontologyService = ontologyService;
        this.catalog = catalog;
        this.store = store;
        this.configurationService = configurationService;
    }

    @EventListener
    public synchronized void onDataChanged(ProjectDataChangedEvent event) {
        usedTypesCache = null;
        usedPredicatesCache = null;
    }

    // =========================
    //  Validation context
    // =========================

    public MatchingConfigurationValidator.DataContext dataContext() {
        if (!ProjectContext.isOpen()) return null;
        Set<String> defined = definedTypes().keySet();
        Set<String> usedTypes = usedTypes();
        Set<String> usedPredicates = usedPredicates();
        Set<String> referenced = new HashSet<>();
        for (MatchLink l : store.read(ProjectContext.getProjectName()).links) {
            if (l.typeGroupId != null) referenced.add(l.typeGroupId);
        }
        return new MatchingConfigurationValidator.DataContext() {
            public Set<String> definedTypes() { return defined; }
            public Set<String> usedTypes() { return usedTypes; }
            public Set<String> usedPredicates() { return usedPredicates; }
            public Set<String> referencedGroupIds() { return referenced; }
        };
    }

    private synchronized Set<String> usedTypes() {
        refreshCaches();
        return usedTypesCache;
    }

    private synchronized Set<String> usedPredicates() {
        refreshCaches();
        return usedPredicatesCache;
    }

    private void refreshCaches() {
        String project = ProjectContext.getProjectName();
        if (usedTypesCache != null && Objects.equals(project, cacheProject)) return;
        Set<String> types = new HashSet<>(), predicates = new HashSet<>();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            for (DataSourceCatalog.DataSourceInfo s : catalog.list()) {
                String g = s.graph().stringValue();
                for (BindingSet bs : select(conn, "SELECT DISTINCT ?t WHERE { GRAPH <" + g + "> { ?s a ?t } }")) {
                    types.add(bs.getValue("t").stringValue());
                }
                for (BindingSet bs : select(conn, "SELECT DISTINCT ?p WHERE { GRAPH <" + g + "> { ?s ?p ?o } }")) {
                    predicates.add(bs.getValue("p").stringValue());
                }
            }
        }
        usedTypesCache = types;
        usedPredicatesCache = predicates;
        cacheProject = project;
    }

    // =========================
    //  Types
    // =========================

    public List<TypeCandidateDto> types() {
        requireProject();
        Map<String, String> prefixes = prefixes();
        Map<String, TypeCandidateDto> out = new LinkedHashMap<>();
        definedTypes().forEach((iri, info) -> {
            TypeCandidateDto t = new TypeCandidateDto();
            t.iri = iri;
            t.curie = MatchingConfigurationWriter.compact(iri, prefixes);
            t.label = info[0];
            t.ontologyNamespace = info[1];
            t.defined = true;
            out.put(iri, t);
        });
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            for (DataSourceCatalog.DataSourceInfo s : catalog.list()) {
                String q = "SELECT ?t (COUNT(DISTINCT ?s) AS ?n) WHERE { GRAPH <" + s.graph().stringValue()
                        + "> { ?s a ?t } FILTER(isIRI(?t)) } GROUP BY ?t";
                for (BindingSet bs : select(conn, q)) {
                    String iri = bs.getValue("t").stringValue();
                    TypeCandidateDto t = out.computeIfAbsent(iri, k -> {
                        TypeCandidateDto x = new TypeCandidateDto();
                        x.iri = k;
                        x.curie = MatchingConfigurationWriter.compact(k, prefixes);
                        return x;
                    });
                    t.usage.put(s.shortName(), longValue(bs.getValue("n")));
                }
            }
        }
        List<TypeCandidateDto> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingLong((TypeCandidateDto t) -> -total(t.usage)).thenComparing(t -> t.curie));
        return list;
    }

    /** IRI → [label, ontology namespace] of every class of the DefinedOntologies. */
    private Map<String, String[]> definedTypes() {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String ns : ontologyService.getConfiguredOntologies().keySet()) {
            OntologySchemaDto schema = ontologyService.getOntologySchema(ns);
            if (schema == null || schema.getTypes() == null) continue;
            for (OntologyClassDto c : schema.getTypes()) {
                if (c.iri != null) out.putIfAbsent(c.iri, new String[]{c.label, ns});
            }
        }
        return out;
    }

    // =========================
    //  Predicates
    // =========================

    public List<PredicateCandidateDto> predicates(List<String> typeIris) {
        requireProject();
        Map<String, String> prefixes = prefixes();
        Set<String> types = expandTypes(typeIris, prefixes, true);
        Map<String, PredicateCandidateDto> out = new LinkedHashMap<>();
        Map<String, long[]> lengths = new HashMap<>();          // predicate → [total length, literal count]
        Map<String, Map<String, Integer>> kinds = new HashMap<>();

        if (!types.isEmpty()) {
            try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
                for (DataSourceCatalog.DataSourceInfo s : catalog.list()) {
                    String q = "SELECT ?s ?p ?o WHERE { GRAPH <" + s.graph().stringValue() + "> { ?s a ?t . VALUES ?t { "
                            + values(types) + "} ?s ?p ?o } } LIMIT " + MAX_ROWS;
                    Map<String, Set<String>> subjects = new HashMap<>();
                    for (BindingSet bs : select(conn, q)) {
                        String p = bs.getValue("p").stringValue();
                        if (p.equals(RDF.TYPE.stringValue())) continue;
                        Value o = bs.getValue("o");
                        subjects.computeIfAbsent(p, k -> new HashSet<>()).add(bs.getValue("s").stringValue());
                        PredicateCandidateDto d = out.computeIfAbsent(p, k -> newPredicate(k, prefixes));
                        String kind = o instanceof IRI ? "iri"
                                : o instanceof Literal l ? "literal:" + MatchingConfigurationWriter.compact(
                                l.getDatatype().stringValue(), prefixes) : "other";
                        kinds.computeIfAbsent(p, k -> new HashMap<>()).merge(kind, 1, Integer::sum);
                        if (o instanceof Literal l) {
                            long[] acc = lengths.computeIfAbsent(p, k -> new long[2]);
                            acc[0] += l.getLabel().length();
                            acc[1]++;
                        }
                        if (d.sampleValues.size() < 3) {
                            String sample = o instanceof Literal l ? l.getLabel() : MatchingConfigurationWriter.compact(o.stringValue(), prefixes);
                            if (!d.sampleValues.contains(sample)) d.sampleValues.add(sample.length() > 80 ? sample.substring(0, 80) + "…" : sample);
                        }
                    }
                    subjects.forEach((p, subs) -> out.get(p).usage.put(s.shortName(), (long) subs.size()));
                }
            }
        }
        for (PredicateCandidateDto d : out.values()) {
            Map<String, Integer> k = kinds.getOrDefault(d.iri, Map.of());
            d.kind = k.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");
            long[] acc = lengths.get(d.iri);
            d.averageLength = acc != null && acc[1] > 0 ? (double) acc[0] / acc[1] : null;
        }

        // properties declared on these types (or their superclasses) by the DefinedOntologies
        Set<String> domains = withAncestors(types);
        for (String ns : ontologyService.getConfiguredOntologies().keySet()) {
            OntologySchemaDto schema = ontologyService.getOntologySchema(ns);
            if (schema == null || schema.getProperties() == null) continue;
            for (OntologyPropertyDto p : schema.getProperties()) {
                if (p.getUri() == null || p.getDomainUri() == null || !domains.contains(p.getDomainUri())) continue;
                PredicateCandidateDto d = out.computeIfAbsent(p.getUri(), k -> {
                    PredicateCandidateDto x = newPredicate(k, prefixes);
                    x.kind = p.getKind() == PropertyKind.OBJECT_PROPERTY ? "iri"
                            : p.getDatatypeUri() != null ? "literal:" + MatchingConfigurationWriter.compact(p.getDatatypeUri(), prefixes)
                            : "unknown";
                    return x;
                });
                d.defined = true;
                if (d.label == null) d.label = p.getLabel();
            }
        }
        for (PredicateCandidateDto d : out.values()) d.suggestedComparator = suggestComparator(d);

        List<PredicateCandidateDto> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingLong((PredicateCandidateDto d) -> -total(d.usage)).thenComparing(d -> d.curie));
        return list;
    }

    private static PredicateCandidateDto newPredicate(String iri, Map<String, String> prefixes) {
        PredicateCandidateDto d = new PredicateCandidateDto();
        d.iri = iri;
        d.curie = MatchingConfigurationWriter.compact(iri, prefixes);
        return d;
    }

    static String suggestComparator(PredicateCandidateDto d) {
        String kind = d.kind == null ? "unknown" : d.kind;
        if (kind.equals("iri")) return ComparatorKind.LINKED_ENTITY.jsonName();
        if (kind.startsWith("literal:")) {
            String datatype = MatchingConfigurationLoader.expand(kind.substring("literal:".length()), Map.of());
            if (DATE_TYPES.contains(datatype)) return ComparatorKind.DATE.jsonName();
            if (NUMBER_TYPES.contains(datatype)) return ComparatorKind.NUMBER.jsonName();
        }
        if (RdfEntityService.LABEL_PREDICATES.contains(d.iri)) return ComparatorKind.NAME.jsonName();
        if (d.averageLength != null && d.averageLength > 100) return ComparatorKind.TEXT.jsonName();
        return ComparatorKind.STRING.jsonName();
    }

    // =========================
    //  Coverage
    // =========================

    public GroupCoverageDto coverage(CoverageRequest request) {
        requireProject();
        if (request == null || request.group == null) throw new BadRequestException("A group is required.");
        Map<String, String> prefixes = new LinkedHashMap<>(prefixes());
        if (request.prefixes != null) prefixes.putAll(request.prefixes);
        boolean subclasses = request.includeSubclasses == null || request.includeSubclasses;
        Set<String> types = expandTypes(request.group.equivalentTypes, prefixes, subclasses);
        GroupCoverageDto out = new GroupCoverageDto();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            for (DataSourceCatalog.DataSourceInfo s : catalog.list()) {
                SourceCoverageDto sc = new SourceCoverageDto();
                sc.dataSource = s.shortName();
                String g = s.graph().stringValue();
                if (!types.isEmpty()) {
                    String typed = "GRAPH <" + g + "> { ?s a ?t . VALUES ?t { " + values(types) + "} }";
                    sc.entityCount = count(conn, "SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE { " + typed + " }");
                    if (request.group.properties != null) {
                        for (Map.Entry<String, MatchingConceptDto> e : request.group.properties.entrySet()) {
                            ConceptCoverageDto cc = new ConceptCoverageDto();
                            List<String> predicates = new ArrayList<>();
                            if (e.getValue() != null && e.getValue().predicates != null) {
                                for (String p : e.getValue().predicates) {
                                    String iri = MatchingConfigurationLoader.expand(p, prefixes);
                                    if (MatchingConfigurationLoader.isAbsoluteIri(iri)) predicates.add(iri);
                                }
                            }
                            if (!predicates.isEmpty() && sc.entityCount > 0) {
                                cc.entitiesWithValue = count(conn, "SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE { " + typed
                                        + " GRAPH <" + g + "> { ?s ?p ?o . VALUES ?p { " + values(new LinkedHashSet<>(predicates)) + "} } }");
                                cc.percent = Math.round(1000.0 * cc.entitiesWithValue / sc.entityCount) / 10.0;
                            }
                            sc.concepts.put(e.getKey(), cc);
                        }
                    }
                }
                out.sources.add(sc);
            }
        }
        return out;
    }

    // =========================
    //  Helpers
    // =========================

    private Map<String, String> prefixes() {
        Map<String, String> out = new LinkedHashMap<>(MatchingConfigurationLoader.IMPLICIT_PREFIXES);
        try {
            out.putAll(configurationService.currentConfiguration().prefixes);
        } catch (RuntimeException ignored) {
            // an unreadable project configuration: the implicit prefixes are enough here
        }
        return out;
    }

    private Set<String> expandTypes(List<String> typeIris, Map<String, String> prefixes, boolean subclasses) {
        Set<String> out = new LinkedHashSet<>();
        if (typeIris == null) return out;
        for (String t : typeIris) {
            String iri = MatchingConfigurationLoader.expand(t, prefixes);
            if (!MatchingConfigurationLoader.isAbsoluteIri(iri)) continue;
            if (subclasses) out.addAll(ontologyService.expandWithSubtypes(iri));
            else out.add(iri);
        }
        return out;
    }

    /** The types and every superclass known from the DefinedOntologies. */
    private Set<String> withAncestors(Set<String> types) {
        Map<String, List<String>> supers = new HashMap<>();
        for (String ns : ontologyService.getConfiguredOntologies().keySet()) {
            OntologySchemaDto schema = ontologyService.getOntologySchema(ns);
            if (schema != null && schema.getHierarchy() != null) {
                schema.getHierarchy().forEach((c, s) -> supers.computeIfAbsent(c, k -> new ArrayList<>()).addAll(s));
            }
        }
        Set<String> out = new LinkedHashSet<>(types);
        Deque<String> todo = new ArrayDeque<>(types);
        while (!todo.isEmpty()) {
            for (String s : supers.getOrDefault(todo.pop(), List.of())) if (out.add(s)) todo.push(s);
        }
        return out;
    }

    private static String values(Collection<String> iris) {
        StringBuilder sb = new StringBuilder();
        for (String i : iris) sb.append('<').append(i).append("> ");
        return sb.toString();
    }

    private static List<BindingSet> select(RepositoryConnection conn, String q) {
        List<BindingSet> out = new ArrayList<>();
        try (TupleQueryResult r = conn.prepareTupleQuery(q).evaluate()) {
            for (BindingSet bs : r) out.add(bs);
        }
        return out;
    }

    private static long count(RepositoryConnection conn, String q) {
        List<BindingSet> rows = select(conn, q);
        return rows.isEmpty() ? 0 : longValue(rows.get(0).getValue("n"));
    }

    private static long longValue(Value v) {
        return v instanceof Literal l ? l.longValue() : 0;
    }

    private static long total(Map<String, Long> usage) {
        long n = 0;
        for (long v : usage.values()) n += v;
        return n;
    }

    private static void requireProject() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("No project is open.");
    }
}
