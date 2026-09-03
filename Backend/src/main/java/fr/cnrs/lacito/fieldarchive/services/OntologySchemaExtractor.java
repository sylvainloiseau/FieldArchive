package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.dtos.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.util.*;
import java.util.stream.Collectors;

public final class OntologySchemaExtractor {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final IRI OWL_MIN_QUALIFIED_CARDINALITY =
            VF.createIRI("http://www.w3.org/2002/07/owl#minQualifiedCardinality");
    private static final IRI OWL_MAX_QUALIFIED_CARDINALITY =
            VF.createIRI("http://www.w3.org/2002/07/owl#maxQualifiedCardinality");
    private static final IRI OWL_QUALIFIED_CARDINALITY =
            VF.createIRI("http://www.w3.org/2002/07/owl#qualifiedCardinality");
    private static final IRI OWL_ON_CLASS =
            VF.createIRI("http://www.w3.org/2002/07/owl#onClass");

    private static final Map<String, DataTypeCategory> XSD_CATEGORY_MAP = buildXsdMap();

    private OntologySchemaExtractor() {}

    public static OntologySchemaDto extract(Model model, String namespacePrefix) {
        List<OntologyClassDto> types = extractTypes(model, namespacePrefix);
        List<OntologyPropertyDto> properties = extractProperties(model, namespacePrefix);
        Map<String, List<String>> hierarchy = extractHierarchy(model, namespacePrefix);
        return new OntologySchemaDto(types, properties, hierarchy);
    }

    // ================= TYPES =================

    private static List<OntologyClassDto> extractTypes(Model model, String namespacePrefix) {
        Set<Resource> classSubjects = new LinkedHashSet<>();
        classSubjects.addAll(model.filter(null, RDF.TYPE, OWL.CLASS).subjects());
        classSubjects.addAll(model.filter(null, RDF.TYPE, RDFS.CLASS).subjects());

        List<OntologyClassDto> result = new ArrayList<>();
        for (Resource subject : classSubjects) {
            if (!(subject instanceof IRI iri)) continue;
            if (namespacePrefix != null && !iri.stringValue().startsWith(namespacePrefix)) continue;

            String label = firstLabel(model, iri).orElse(localName(iri.stringValue()));
            result.add(new OntologyClassDto(iri.stringValue(), localName(iri.stringValue()), label));
        }
        //result.sort(Comparator.comparing(OntologyClassDto::getLocalName));
        return result;
    }

    // ================= PROPERTIES =================

    private static List<OntologyPropertyDto> extractProperties(Model model, String namespacePrefix) {

        Map<IRI, PropertyKind> propertyKinds = new LinkedHashMap<>();
        model.filter(null, RDF.TYPE, OWL.OBJECTPROPERTY).subjects().forEach(s -> {
            if (s instanceof IRI iri) propertyKinds.put(iri, PropertyKind.OBJECT_PROPERTY);
        });
        model.filter(null, RDF.TYPE, OWL.DATATYPEPROPERTY).subjects().forEach(s -> {
            if (s instanceof IRI iri) propertyKinds.put(iri, PropertyKind.DATA_PROPERTY);
        });
        // rdf:Property "brut" (ex. FOAF) : le genre sera déduit du range
        model.filter(null, RDF.TYPE, RDF.PROPERTY).subjects().forEach(s -> {
            if (s instanceof IRI iri) propertyKinds.putIfAbsent(iri, PropertyKind.UNKNOWN);
        });

        Set<IRI> functionalProperties = model.filter(null, RDF.TYPE, OWL.FUNCTIONALPROPERTY).subjects().stream()
                .filter(s -> s instanceof IRI)
                .map(s -> (IRI) s)
                .collect(Collectors.toSet());

        Map<PropertyContextKey, CardinalityDto> restrictionCardinalities = new LinkedHashMap<>();
        Map<PropertyContextKey, List<IRI>> restrictionRangeOverride = new LinkedHashMap<>();
        collectRestrictions(model, restrictionCardinalities, restrictionRangeOverride);

        List<OntologyPropertyDto> result = new ArrayList<>();

        for (Map.Entry<IRI, PropertyKind> entry : propertyKinds.entrySet()) {
            IRI propertyIri = entry.getKey();
            if (namespacePrefix != null && !propertyIri.stringValue().startsWith(namespacePrefix)) continue;

            PropertyKind kind = entry.getValue();

            List<IRI> domainIris = model.filter(propertyIri, RDFS.DOMAIN, null).objects().stream()
                    .filter(v -> v instanceof IRI).map(v -> (IRI) v).collect(Collectors.toList());

            // Range(s): resolve plain IRI ranges AND owl:unionOf ranges into a flat list
            List<IRI> globalRanges = new ArrayList<>();
            for (Value v : model.filter(propertyIri, RDFS.RANGE, null).objects()) {
                if (v instanceof Resource r) {
                    globalRanges.addAll(resolveRangeIris(model, r));
                }
            }
            IRI globalRangeForDatatype = globalRanges.isEmpty() ? null : globalRanges.get(0);

            if (kind == PropertyKind.UNKNOWN) {
                kind = (globalRangeForDatatype != null && isDatatype(globalRangeForDatatype))
                        ? PropertyKind.DATA_PROPERTY : PropertyKind.OBJECT_PROPERTY;
            }

            boolean isFunctional = functionalProperties.contains(propertyIri);
            String label = firstLabel(model, propertyIri).orElse(localName(propertyIri.stringValue()));

            List<PropertyContextKey> restrictionContexts = restrictionCardinalities.keySet().stream()
                    .filter(k -> k.property().equals(propertyIri))
                    .collect(Collectors.toList());

            Set<IRI> allDomainContexts = new LinkedHashSet<>(domainIris);
            restrictionContexts.forEach(k -> allDomainContexts.add(k.domainClass()));
            if (allDomainContexts.isEmpty()) allDomainContexts.add(null);

            for (IRI domainCtx : allDomainContexts) {
                OntologyPropertyDto dto = new OntologyPropertyDto();
                dto.setUri(propertyIri.stringValue());
                dto.setLocalName(localName(propertyIri.stringValue()));
                dto.setLabel(label);
                dto.setKind(kind);

                if (domainCtx != null) {
                    dto.setDomainUri(domainCtx.stringValue());
                    dto.setDomainLocalName(localName(domainCtx.stringValue()));
                }

                PropertyContextKey ctxKey = domainCtx != null ? new PropertyContextKey(propertyIri, domainCtx) : null;
                List<IRI> effectiveRanges = (ctxKey != null && restrictionRangeOverride.containsKey(ctxKey))
                        ? restrictionRangeOverride.get(ctxKey) : globalRanges;

                if (kind == PropertyKind.OBJECT_PROPERTY) {
                    List<OntologyRangeDto> rangeDtos = effectiveRanges.stream()
                            .map(r -> new OntologyRangeDto(r.stringValue(), localName(r.stringValue())))
                            .collect(Collectors.toList());
                    dto.setRanges(rangeDtos);
                } else if (kind == PropertyKind.DATA_PROPERTY) {
                    IRI effectiveRange = effectiveRanges.isEmpty() ? null : effectiveRanges.get(0);
                    applyDatatypeInfo(dto, effectiveRange);
                }

                CardinalityDto cardinality = new CardinalityDto();
                if (isFunctional) cardinality.setMax(1);

                if (ctxKey != null && restrictionCardinalities.containsKey(ctxKey)) {
                    CardinalityDto fromRestriction = restrictionCardinalities.get(ctxKey);
                    if (fromRestriction.getMin() != null) cardinality.setMin(fromRestriction.getMin());
                    if (fromRestriction.getMax() != null
                            && (cardinality.getMax() == null || fromRestriction.getMax() < cardinality.getMax())) {
                        cardinality.setMax(fromRestriction.getMax());
                    }
                }
                dto.setCardinality(cardinality);
                result.add(dto);
            }
        }

        result.sort(Comparator.comparing(OntologyPropertyDto::getLocalName)
                .thenComparing(p -> Optional.ofNullable(p.getDomainLocalName()).orElse("")));
        return result;
    }

    // ================= RESTRICTIONS (owl:Restriction) =================

    private static void collectRestrictions(Model model,
                                            Map<PropertyContextKey, CardinalityDto> cardinalities,
                                            Map<PropertyContextKey, List<IRI>> rangeOverrides) {

        Set<Statement> hostLinks = new LinkedHashSet<>();
        hostLinks.addAll(model.filter(null, RDFS.SUBCLASSOF, null));
        hostLinks.addAll(model.filter(null, OWL.EQUIVALENTCLASS, null));

        for (Statement link : hostLinks) {
            if (!(link.getSubject() instanceof IRI hostClass)) continue;
            if (!(link.getObject() instanceof Resource restriction)) continue;
            if (!model.contains(restriction, RDF.TYPE, OWL.RESTRICTION)) continue;

            IRI onProperty = getFirstIri(model, restriction, OWL.ONPROPERTY);
            if (onProperty == null) continue;

            PropertyContextKey key = new PropertyContextKey(onProperty, hostClass);
            CardinalityDto cardinality = cardinalities.computeIfAbsent(key, k -> new CardinalityDto());

            applyCardinality(model, restriction, OWL.MINCARDINALITY, cardinality, true);
            applyCardinality(model, restriction, OWL.MAXCARDINALITY, cardinality, false);
            applyExactCardinality(model, restriction, OWL.CARDINALITY, cardinality);

            applyCardinality(model, restriction, OWL_MIN_QUALIFIED_CARDINALITY, cardinality, true);
            applyCardinality(model, restriction, OWL_MAX_QUALIFIED_CARDINALITY, cardinality, false);
            applyExactCardinality(model, restriction, OWL_QUALIFIED_CARDINALITY, cardinality);

            // onClass / allValuesFrom may itself be a union -> resolve to a list
            List<IRI> onClassRanges = new ArrayList<>();
            Resource onClassNode = getFirstResource(model, restriction, OWL_ON_CLASS);
            if (onClassNode == null) onClassNode = getFirstResource(model, restriction, OWL.ALLVALUESFROM);
            if (onClassNode != null) onClassRanges.addAll(resolveRangeIris(model, onClassNode));
            if (!onClassRanges.isEmpty()) rangeOverrides.put(key, onClassRanges);
        }
    }

    private static void applyCardinality(Model model, Resource restriction, IRI predicate,
                                         CardinalityDto cardinality, boolean isMin) {
        model.filter(restriction, predicate, null).objects().stream()
                .filter(v -> v instanceof Literal).map(v -> (Literal) v).findFirst()
                .ifPresent(lit -> {
                    try {
                        int value = Integer.parseInt(lit.getLabel());
                        if (isMin) cardinality.setMin(value); else cardinality.setMax(value);
                    } catch (NumberFormatException ignored) {}
                });
    }

    private static void applyExactCardinality(Model model, Resource restriction, IRI predicate,
                                              CardinalityDto cardinality) {
        model.filter(restriction, predicate, null).objects().stream()
                .filter(v -> v instanceof Literal).map(v -> (Literal) v).findFirst()
                .ifPresent(lit -> {
                    try {
                        int value = Integer.parseInt(lit.getLabel());
                        cardinality.setMin(value);
                        cardinality.setMax(value);
                    } catch (NumberFormatException ignored) {}
                });
    }

    // ================= RANGE / UNION RESOLUTION =================

    /**
     * Resolves a range node into a flat list of IRIs.
     * - Plain IRI -> singleton list.
     * - Blank node owl:Class with owl:unionOf -> walks the RDF collection,
     *   flattening nested unions.
     * - Anything else -> empty list.
     */
    private static List<IRI> resolveRangeIris(Model model, Resource rangeNode) {
        Optional<Resource> unionListHead = model.filter(rangeNode, OWL.UNIONOF, null).objects().stream()
                .filter(v -> v instanceof Resource).map(v -> (Resource) v).findFirst();

        if (unionListHead.isPresent()) {
            List<IRI> result = new ArrayList<>();
            for (Resource member : readRdfCollection(model, unionListHead.get())) {
                result.addAll(resolveRangeIris(model, member)); // flatten nested unions
            }
            return result;
        }

        if (rangeNode instanceof IRI iri) {
            return Collections.singletonList(iri);
        }
        return Collections.emptyList();
    }

    /** Reads an rdf:List (rdf:first/rdf:rest/rdf:nil) into an ordered list of its member resources. */
    private static List<Resource> readRdfCollection(Model model, Resource listHead) {
        List<Resource> items = new ArrayList<>();
        Resource current = listHead;
        Set<Resource> visited = new HashSet<>(); // guard against malformed cyclic lists
        while (current != null && !current.equals(RDF.NIL) && visited.add(current)) {
            model.filter(current, RDF.FIRST, null).objects().stream()
                    .filter(v -> v instanceof Resource).map(v -> (Resource) v).findFirst()
                    .ifPresent(items::add);

            current = model.filter(current, RDF.REST, null).objects().stream()
                    .filter(v -> v instanceof Resource).map(v -> (Resource) v).findFirst()
                    .orElse(null);
        }
        return items;
    }

    // ================= DATATYPES =================

    private static void applyDatatypeInfo(OntologyPropertyDto dto, IRI range) {
        if (range == null) {
            dto.setDatatypeCategory(DataTypeCategory.LITERAL);
            return;
        }
        String uri = range.stringValue();

        if (uri.equals(RDF.LANGSTRING.stringValue())) {
            dto.setDatatypeCategory(DataTypeCategory.STRING);
            dto.setLanguage("*");
            dto.setDatatypeUri(uri);
            return;
        }
        if (uri.equals(RDFS.LITERAL.stringValue())) {
            dto.setDatatypeCategory(DataTypeCategory.LITERAL);
            dto.setDatatypeUri(uri);
            return;
        }

        dto.setDatatypeUri(uri);
        dto.setDatatypeCategory(XSD_CATEGORY_MAP.getOrDefault(uri, DataTypeCategory.LITERAL));
    }

    private static boolean isDatatype(IRI range) {
        String uri = range.stringValue();
        return uri.startsWith(XSD.NAMESPACE)
                || uri.equals(RDFS.LITERAL.stringValue())
                || uri.equals(RDF.LANGSTRING.stringValue());
    }

    private static Map<String, DataTypeCategory> buildXsdMap() {
        Map<String, DataTypeCategory> map = new HashMap<>();
        put(map, DataTypeCategory.STRING, "string");
        put(map, DataTypeCategory.BOOLEAN, "boolean");
        put(map, DataTypeCategory.INTEGER,
                "integer", "int", "long", "short", "byte",
                "positiveInteger", "nonNegativeInteger", "negativeInteger", "nonPositiveInteger",
                "unsignedInt", "unsignedLong", "unsignedShort", "unsignedByte");
        put(map, DataTypeCategory.DECIMAL, "decimal");
        put(map, DataTypeCategory.FLOAT, "float", "double");
        put(map, DataTypeCategory.DATETIME,
                "date", "time", "dateTime", "dateTimeStamp",
                "gYear", "gMonth", "gDay", "gYearMonth", "gMonthDay",
                "duration", "yearMonthDuration", "dayTimeDuration");
        put(map, DataTypeCategory.BINARY, "hexBinary", "base64Binary");
        put(map, DataTypeCategory.URI, "anyURI");
        return map;
    }

    private static void put(Map<String, DataTypeCategory> map, DataTypeCategory category, String... localNames) {
        for (String ln : localNames) map.put(XSD.NAMESPACE + ln, category);
    }

    // ================= UTILITAIRES =================

    private static IRI getFirstIri(Model model, Resource subject, IRI predicate) {
        return model.filter(subject, predicate, null).objects().stream()
                .filter(v -> v instanceof IRI).map(v -> (IRI) v).findFirst().orElse(null);
    }

    private static Resource getFirstResource(Model model, Resource subject, IRI predicate) {
        return model.filter(subject, predicate, null).objects().stream()
                .filter(v -> v instanceof Resource).map(v -> (Resource) v).findFirst().orElse(null);
    }

    private static Optional<String> firstLabel(Model model, IRI subject) {
        return model.filter(subject, RDFS.LABEL, null).objects().stream()
                .filter(v -> v instanceof Literal).map(v -> (Literal) v)
                .filter(lit -> {
                    String lang = lit.getLanguage().orElse("");
                    return lang.isEmpty() || lang.equals("en") || lang.equals("fr");
                })
                .map(Literal::getLabel).findFirst();
    }

    private static String localName(String uri) {
        if (uri.contains("#")) return uri.substring(uri.indexOf('#') + 1);
        int lastSlash = uri.lastIndexOf('/');
        return (lastSlash != -1) ? uri.substring(lastSlash + 1) : uri;
    }
    // ================= CLASS HIERARCHY =================

    /**
     * Builds a map: class URI -> list of its direct superclass URIs (rdfs:subClassOf),
     * restricted to plain named classes (blank-node owl:Restriction objects are ignored).
     */
    private static Map<String, List<String>> extractHierarchy(Model model, String namespacePrefix) {
        Map<String, List<String>> hierarchy = new LinkedHashMap<>();

        for (Statement st : model.filter(null, RDFS.SUBCLASSOF, null)) {
            if (!(st.getSubject() instanceof IRI subIri)) continue;
            if (!(st.getObject() instanceof IRI superIri)) continue; // skips owl:Restriction blank nodes

            if (namespacePrefix != null && !subIri.stringValue().startsWith(namespacePrefix)) continue;

            hierarchy.computeIfAbsent(subIri.stringValue(), k -> new ArrayList<>())
                    .add(superIri.stringValue());
        }

        return hierarchy;
    }

    private record PropertyContextKey(IRI property, IRI domainClass) {}
}