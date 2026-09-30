package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class RdfEntityService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private final ProjectService projectService;
    private final DataSourceService dsService;
    private final OntologyService ontologyService;
    private final InternalDataBookkeeping bookkeeping;
    private final ApplicationEventPublisher events;

    /**
     * Properties read, in this order, to name an entity. The single source of truth for
     * {@link #bestLabel}, the label search and the natural-language agent's prompt.
     */
    public static final List<String> LABEL_PREDICATES = List.of(
            RdfNamespaces.RICO + "name",
            "http://www.w3.org/2000/01/rdf-schema#label",
            "http://purl.org/dc/terms/title",
            "http://xmlns.com/foaf/0.1/name"
    );

    public RdfEntityService(ProjectService projectService, DataSourceService dsService, OntologyService ontologyService,
                            InternalDataBookkeeping bookkeeping, ApplicationEventPublisher events) {
        this.projectService = projectService;
        this.dsService = dsService;
        this.ontologyService = ontologyService;
        this.bookkeeping = bookkeeping;
        this.events = events;
    }
    private IRI internalCtx() {
        String projectName = projectService.readCurrentProject().name;
        return dsService.getGraphIri(projectName + "_internal");
    }

    private static final Map<String, String> PREFIX = Map.of(
            "app", RdfNamespaces.APP,
            "ric", RdfNamespaces.RICO,
            "rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
            "xsd", "http://www.w3.org/2001/XMLSchema#",
            "dcterms", "http://purl.org/dc/terms/",
            "foaf", "http://xmlns.com/foaf/0.1/"
    );

    private IRI iriFromKey(String entityIri, String key) {
        String typeIri = expand(entityIri);
        String entityTypeName = typeIri.substring(Math.max(typeIri.lastIndexOf('#'), typeIri.lastIndexOf('/')) + 1);
        ProjectDto currentProject = this.projectService.readCurrentProject();
        return vf.createIRI(currentProject.prefix + '/' + entityTypeName +'/' + key);
    }

    /** A new entity IRI, {@code <project prefix>/<typeLocalName>/<uuid>}, the scheme used for every created entity. */
    public IRI mintIri(String typeIri) {
        requireProjectOpen();
        return iriFromKey(typeIri, UUID.randomUUID().toString());
    }

    private String keyFromIri(IRI iri) {
        String s = iri.stringValue();
        String entityNs = projectService.readCurrentProject().prefix;

//        || !s.startsWith(entityNs)
        if (entityNs == null ) {
            throw new IllegalArgumentException("invalid IRI: " + s);
        }

        int lastSlash = s.lastIndexOf('/');

        if (lastSlash == -1 || lastSlash == s.length() - 1) {
            throw new IllegalArgumentException("IRI mal formée (pas de clé): " + s);
        }

        return s.substring(lastSlash + 1);
    }

    private void requireProjectOpen() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("Aucun projet ouvert.");
    }

    private String expand(String iriOrCurie) {
        if (iriOrCurie == null || iriOrCurie.isBlank()) {
            throw new BadRequestException("IRI/CURIE vide.");
        }
        String s = iriOrCurie.trim();
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("urn:")) return s;

        int idx = s.indexOf(':');
        if (idx <= 0) throw new BadRequestException("CURIE invalide: " + s);

        String p = s.substring(0, idx);
        String local = s.substring(idx + 1);
        String ns = PREFIX.get(p);
        if (ns == null) throw new BadRequestException("Prefix inconnu: " + p);

        return ns + local;
    }

    private boolean isInternalEntity(RepositoryConnection conn, IRI subject) {
        // Si l'entité a au moins un triplet dans le graphe interne, on la considère interne/éditable
        IRI CTX_INTERNAL = internalCtx();

        try (var stmts = conn.getStatements(subject, null, null, CTX_INTERNAL)) {
            return stmts.hasNext();
        }
    }

    private List<String> readTypes(RepositoryConnection conn, IRI subject) {
        List<String> out = new ArrayList<>();
        try (var stmts = conn.getStatements(subject, RDF.TYPE, null)) {
            while (stmts.hasNext()) {
                Value o = stmts.next().getObject();
                if (o.isIRI()) out.add(o.stringValue());
            }
        }
        return out;
    }

    private String bestLabel(RepositoryConnection conn, IRI subject) {

        // 0-3) rico:name (TOP PRIORITY), rdfs:label, dcterms:title, foaf:name
        for (String labelPredicate : LABEL_PREDICATES) {
            try (var st = conn.getStatements(subject, vf.createIRI(labelPredicate), null)) {
                if (st.hasNext()) return st.next().getObject().stringValue();
            }
        }

        // 4) fallback: ANY literal except dates
        try (var st = conn.getStatements(subject, null, null)) {
            while (st.hasNext()) {
                Value o = st.next().getObject();
                if (o.isLiteral() && !(o instanceof Literal l && l.getDatatype().equals(XSD.DATETIME))) {
                    return o.stringValue();
                }
            }
        }

        // 5) final fallback
        return subject.stringValue();
    }

    private String getModificationDate(RepositoryConnection conn, IRI subject) {
        IRI modified = vf.createIRI("http://purl.org/dc/terms/modified");

        try (var st = conn.getStatements(subject, modified, null)) {
            if (st.hasNext()) {
                Value o = st.next().getObject();
                if (o.isLiteral()) {
                    return o.stringValue();
                }
            }
        }
        return null;
    }

    private String getCreationDate(RepositoryConnection conn, IRI subject) {
        IRI created = vf.createIRI("http://purl.org/dc/terms/created");

        try (var st = conn.getStatements(subject, created, null)) {
            if (st.hasNext()) {
                Value o = st.next().getObject();
                if (o.isLiteral()) {
                    return o.stringValue();
                }
            }
        }
        return null;
    }

    // =========================
    //  CREATE
    // =========================
    public RdfEntityDto create(CreateRdfEntityRequest req) {
        requireProjectOpen();
        if (req == null) throw new BadRequestException("Body is missing.");
        if (req.types == null || req.types.isEmpty()) {
            throw new BadRequestException("Entity type is mandatory (at least one)");
        }
        for (TypeRefDto t : req.types) {
            if (t == null || t.iri == null || t.iri.isBlank()) {
                throw new BadRequestException("Each entity type must have a non-empty iri");
            }
        }

        String entityKey = UUID.randomUUID().toString();
        IRI subject = iriFromKey(req.types.get(0).iri, entityKey);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            conn.begin();
            IRI CTX_INTERNAL = internalCtx();

            // rdf:type
            for (TypeRefDto t : req.types) {
                IRI typeIri = vf.createIRI(expand(t.iri));
                conn.add(subject, RDF.TYPE, typeIri, CTX_INTERNAL);
            }

            IRI createdPredicate = vf.createIRI("http://purl.org/dc/terms/created");
            Literal createdLiteral = vf.createLiteral(Instant.now().toString(), XSD.DATETIME);
            conn.add(subject, createdPredicate, createdLiteral, CTX_INTERNAL);

            // properties
            if (req.properties != null) {
                for (RdfPropertyDto p : req.properties) {
                    addProperty(conn, subject, p);
                }
            }
            bookkeeping.touchInternalDataSource(conn);
            conn.commit();
        }
        events.publishEvent(new ProjectDataChangedEvent(ProjectContext.getProjectName()));

        return getByIri(subject);
    }

    private void addType(RepositoryConnection conn, IRI subject, String type){
        if (type == null) return ;
        IRI CTX_INTERNAL = internalCtx();
        IRI typeIri = vf.createIRI(expand(type));
        conn.add(subject, RDF.TYPE, typeIri, CTX_INTERNAL);
    }

    private void addProperty(RepositoryConnection conn, IRI subject, RdfPropertyDto p) {
        if (p == null) return;
        if (p.predicate == null || p.predicate.isBlank()) {
            throw new BadRequestException("Predicate is mandatory!");
        }
        if (p.kind == null || p.kind.isBlank()) {
            throw new BadRequestException("Predicate's kind is mandatory (literal|iri).");
        }
        if (p.values == null || p.values.isEmpty()) {
            throw new BadRequestException("At least one value is mandatory for predicate: " + p.predicate);
        }

        IRI CTX_INTERNAL = internalCtx();
        IRI pred = vf.createIRI(expand(p.predicate));

        for (RdfValueDto v : p.values) {
            addValue(conn, subject, pred, CTX_INTERNAL, p.kind, v);
        }
    }

    private void addValue(RepositoryConnection conn, IRI subject, IRI pred, IRI ctx, String kind, RdfValueDto v) {
        if (v == null) return;

        if ("iri".equalsIgnoreCase(kind)) {
            if (v.value == null || v.value.isBlank()) throw new BadRequestException("value obligatoire pour kind=iri.");
            IRI obj = vf.createIRI(expand(v.value));
            conn.add(subject, pred, obj, ctx);
            return;
        }

        if (!"literal".equalsIgnoreCase(kind)) {
            throw new BadRequestException("kind invalide: " + kind);
        }

        if (v.value == null) throw new BadRequestException("value obligatoire pour kind=literal.");

        Literal lit;
        if (v.lang != null && !v.lang.isBlank()) {
            lit = vf.createLiteral(v.value, v.lang.trim());
        } else if (v.datatype != null && !v.datatype.isBlank()) {
            IRI dt = vf.createIRI(expand(v.datatype));
            lit = vf.createLiteral(v.value, dt);
        } else {
            lit = vf.createLiteral(v.value);
        }

        conn.add(subject, pred, lit, ctx);
    }

    public List<RdfEntitySummaryDto> listByTypes(List<String> typeCuriesOrIris, boolean includeSubtypes) {
        requireProjectOpen();
        if (typeCuriesOrIris == null || typeCuriesOrIris.isEmpty()) {
            throw new BadRequestException("Type parameter is mandatory.");
        }

        // Union, across every declared range, of (range ∪ its transitive subtypes).
        Set<IRI> candidateTypes = new LinkedHashSet<>();
        for (String typeCurieOrIri : typeCuriesOrIris) {
            String seedIri = expand(typeCurieOrIri);
            Set<String> seeds = includeSubtypes
                    ? ontologyService.expandWithSubtypes(seedIri)
                    : Set.of(seedIri);
            for (String s : seeds) candidateTypes.add(vf.createIRI(s));
        }

        // De-dupe by subject: an entity may match more than one candidate type
        // (e.g. two declared ranges whose subtype trees overlap). For each subject, also
        // record which of the candidate types it is actually asserted with (matchedTypes),
        // so callers can group results by the entity's own type rather than the requested range.
        Map<IRI, RdfEntitySummaryDto> bySubject = new LinkedHashMap<>();
        Map<IRI, Set<String>> matchedTypesBySubject = new LinkedHashMap<>();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            for (IRI typeIri : candidateTypes) {
                try (var stmts = conn.getStatements(null, RDF.TYPE, typeIri)) {
                    while (stmts.hasNext()) {
                        Resource s = stmts.next().getSubject();
                        if (!(s instanceof IRI subject)) continue;
                        bySubject.computeIfAbsent(subject, subj -> {
                            boolean internal = isInternalEntity(conn, subj);
                            RdfEntitySummaryDto dto = new RdfEntitySummaryDto();
                            dto.entityKey = keyFromIri(subj);
                            dto.iri = subj.stringValue();
                            dto.source = internal ? "internal" : "external";
                            dto.editable = internal;
                            dto.label = bestLabel(conn, subj);
                            dto.creationDate = getCreationDate(conn, subj);
                            dto.modificationDate = getModificationDate(conn, subj);
                            return dto;
                        });
                        matchedTypesBySubject
                                .computeIfAbsent(subject, k -> new LinkedHashSet<>())
                                .add(typeIri.stringValue());
                    }
                }
            }
        }

        bySubject.forEach((subject, dto) ->
                dto.matchedTypes = new ArrayList<>(matchedTypesBySubject.get(subject)));

        List<RdfEntitySummaryDto> out = new ArrayList<>(bySubject.values());
        out.sort(Comparator.comparing(a -> a.label == null ? "" : a.label));
        return out;
    }

    public List<RdfEntitySummaryDto> listWithoutType() {

        requireProjectOpen();

        String sparql = """
        SELECT DISTINCT ?s WHERE {
            ?s ?p ?o .
            FILTER(isIRI(?s))
            FILTER NOT EXISTS { ?s a ?anyType }
        }
        """;

        List<RdfEntitySummaryDto> out = new ArrayList<>();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql);

            try (TupleQueryResult result = query.evaluate()) {
                while (result.hasNext()) {
                    BindingSet bs = result.next();
                    Value v = bs.getValue("s");
                    if (!(v instanceof IRI subject)) continue;

                    boolean internal = isInternalEntity(conn, subject);

                    RdfEntitySummaryDto dto = new RdfEntitySummaryDto();
                    dto.entityKey = keyFromIri(subject);
                    dto.iri = subject.stringValue();
                    dto.source = internal ? "internal" : "external";
                    dto.editable = internal;
                    dto.label = bestLabel(conn, subject);
                    dto.creationDate = getCreationDate(conn, subject);
                    dto.modificationDate = getModificationDate(conn, subject);

                    out.add(dto);
                }
            }
        }

        out.sort(Comparator.comparing(a -> a.label == null ? "" : a.label));
        return out;
    }


    public String getNameOfEntityByIri(IRI subject) {
            requireProjectOpen();
            if (subject == null) {
                throw new BadRequestException("IRI manquant.");
            }
            try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
                boolean exists;
                try (var st = conn.getStatements(subject, null, null)) {
                    exists = st.hasNext();
                }
                if (!exists) throw new NotFoundException("Entité introuvable: " + subject.stringValue());
                IRI CTX_INTERNAL = internalCtx();
                // properties (on renvoie tout ce qu’on trouve)
                // Lire toutes les propriétés
                try (var stmts = conn.getStatements(subject, null, null, CTX_INTERNAL)) {
                    while (stmts.hasNext()) {
                        Statement st = stmts.next();
                        IRI pred = st.getPredicate();
                        Value obj = st.getObject();

                        // Ignore rdf:type (déjà traité)
                        String predicateString = pred.stringValue();
                        if (!predicateString.equals("https://www.ica.org/standards/RiC/ontology#name") ) continue;


                        if (obj.isLiteral()) {
                            Literal lit = (Literal) obj;
                            return lit.getLabel();

                        }


                    }
                }
            }
            return "";

    }

    public RdfEntityDto getByKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("IRI is missing.");
        }
        return getByIri(vf.createIRI(key));
    }

    public RdfEntityDto getByIri(IRI subject) {
        requireProjectOpen();
        if (subject == null) {
            throw new BadRequestException("IRI is missing.");
        }

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            boolean exists;
            try (var st = conn.getStatements(subject, null, null)) {
                exists = st.hasNext();
            }
            if (!exists) throw new NotFoundException("Entity not found: " + subject.stringValue());

            boolean internal = isInternalEntity(conn, subject);
            IRI CTX_INTERNAL = internalCtx();

            RdfEntityDto dto = new RdfEntityDto();
            dto.entityKey = keyFromIri(subject);
            dto.iri = subject.stringValue();
            dto.source = internal ? "internal" : "external";
            dto.editable = internal;

            Map<String, RdfPropertyDto> byPredicate = new LinkedHashMap<>();
            // cache graph -> shortName resolution within this call, avoid re-querying per triple
            Map<Resource, String> shortNameCache = new HashMap<>();
            // de-dupe (type, source) pairs in case the same type is asserted in multiple graphs
            Map<String, RdfTypeDto> typesByKey = new LinkedHashMap<>();

            try (var stmts = conn.getStatements(subject, null, null)) {
                while (stmts.hasNext()) {
                    Statement st = stmts.next();
                    IRI pred = st.getPredicate();
                    Value obj = st.getObject();
                    Resource ctx = st.getContext();

                    boolean fromInternal = ctx != null && ctx.equals(CTX_INTERNAL);
                    String source = fromInternal ? "internal" : "external";
                    String shortName = fromInternal
                            ? "internal"
                            : (ctx instanceof IRI ? shortNameCache.computeIfAbsent(
                            ctx, c -> dsService.getShortNameByGraph((IRI) c))
                            : null);

                    if (pred.equals(RDF.TYPE)) {
                        if (!obj.isIRI()) continue; // ignore malformed type triples

                        String typeIri = obj.stringValue();
                        // key by type+source so the same type from two different graphs
                        // of the same "kind" collapses, but internal vs external stays distinct
                        String key = typeIri + "|" + source;

                        typesByKey.computeIfAbsent(key, k -> {
                            RdfTypeDto t = new RdfTypeDto();
                            t.iri = typeIri;
                            t.source = source;
                            t.datasourceShortName = shortName;
                            return t;
                        });
                        continue;
                    }

                    String predKey = pred.stringValue();
                    RdfPropertyDto p = byPredicate.computeIfAbsent(predKey, k -> {
                        RdfPropertyDto newP = new RdfPropertyDto();
                        newP.predicate = predKey;
                        newP.schema = ontologyService.getPropertyByUri(predKey);
                        return newP;
                    });

                    RdfValueDto v = new RdfValueDto();
                    v.source = source;
                    v.datasourceShortName = shortName;

                    if (obj.isIRI()) {
                        if (p.kind == null) p.kind = "iri";
                        v.value = obj.stringValue();
                        v.name = this.getNameOfEntityByIri((IRI) obj);
                    } else if (obj.isLiteral()) {
                        Literal lit = (Literal) obj;
                        if (p.kind == null) p.kind = "literal";
                        v.value = lit.getLabel();
                        v.datatype = lit.getDatatype() != null ? lit.getDatatype().stringValue() : null;
                        v.lang = lit.getLanguage().orElse(null);
                    } else {
                        if (p.kind == null) p.kind = "other";
                        v.value = obj.stringValue();
                    }

                    p.values.add(v);
                }
            }

            dto.types.addAll(typesByKey.values());
            dto.properties.addAll(byPredicate.values());
            return dto;
        }
    }    //  UPDATE
    // =========================
    // =========================
    //  UPDATE (Only internal)
    // =========================
    public RdfEntityDto updateByKey(String entityIri, UpdateRdfEntityRequest req) {

        requireProjectOpen();

        if (entityIri == null || entityIri.isBlank()) {
            throw new BadRequestException("entityKey is missing.");
        }
        if (req == null) {
            throw new BadRequestException("Request Body is missing.");
        }

        IRI subject = vf.createIRI(entityIri);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            boolean exists;
            try (var st = conn.getStatements(subject, null, null)) {
                exists = st.hasNext();
            }
            if (!exists) {
                throw new NotFoundException("Entity not found : " + entityIri);
            }

            conn.begin();

            IRI CTX_INTERNAL = internalCtx();
            if (req.properties != null) {

                for (RdfPropertyDto p : req.properties) {

                    if (p == null || p.predicate == null || p.predicate.isBlank()) {
                        continue;
                    }

                    IRI pred = vf.createIRI(expand(p.predicate));
                    conn.remove(subject, pred, null, CTX_INTERNAL);

                    if(p.values.isEmpty()){
                        conn.remove(subject, pred, null, CTX_INTERNAL);
                    }

                    if (p.values != null && !p.values.isEmpty()) {
                        addProperty(conn, subject, p);
                    }
                }
            }

            if (req.types != null) {
                conn.remove(subject, RDF.TYPE, null, CTX_INTERNAL);

                for (String type : req.types) {
                    if (type == null) continue;
                    addType(conn, subject, type);
                }
            }

            IRI modifiedPredicate = vf.createIRI("http://purl.org/dc/terms/modified");
            conn.remove(subject, modifiedPredicate, null, CTX_INTERNAL);
            Literal modifiedLiteral = vf.createLiteral(Instant.now().toString(), XSD.DATETIME);
            conn.add(subject, modifiedPredicate, modifiedLiteral, CTX_INTERNAL);

            bookkeeping.touchInternalDataSource(conn);

            conn.commit();
        }
        events.publishEvent(new ProjectDataChangedEvent(ProjectContext.getProjectName()));

        return getByIri(subject);
    }
    //  DELETE (interne uniquement)
// =========================
    public void deleteByKey(String entityIri) {

        requireProjectOpen();

        if (entityIri == null || entityIri.isBlank()) {
            throw new BadRequestException("entityKey manquant.");
        }

        IRI subject = vf.createIRI(entityIri);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            // 1 Vérifier existence
            boolean exists;
            try (var st = conn.getStatements(subject, null, null)) {
                exists = st.hasNext();
            }
            if (!exists) {
                throw new NotFoundException("Entity not found: " + entityIri);
            }

            // 2 Vérifier éditable
            if (!isInternalEntity(conn, subject)) {
                throw new BadRequestException(
                        "Deletion of entity is not allowed : Entity is from an external DataSource"
                );
            }
            IRI CTX_INTERNAL = internalCtx();
            conn.begin();
            // 3 Supprimer UNIQUEMENT dans la source interne
            //Supprimer tous les triplets où l'entité est SUJET
            //    ex: Jean Dupont → rico:name → "Jean"
            conn.remove(subject, null, null, CTX_INTERNAL);
            //Supprimer tous les trilplets où l'entité est objet
            //    ex: Photo-001 → rico:creator → Jean Dupont  ← on supprime ça aussi
            conn.remove((Resource) null, null, subject, CTX_INTERNAL);

            // 4 Mettre à jour lastSync
            bookkeeping.touchInternalDataSource(conn);

            conn.commit();
        }
        events.publishEvent(new ProjectDataChangedEvent(ProjectContext.getProjectName()));
    }

    // =========================
    //  Helpers for the natural-language agent
    // =========================

    /** Name of an entity, in the {@link #LABEL_PREDICATES} order; null if the entity does not exist. */
    public String bestLabel(IRI subject) {
        requireProjectOpen();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(subject, null, null, false)) return null;
            return bestLabel(conn, subject);
        }
    }

    public boolean exists(IRI subject) {
        requireProjectOpen();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            return conn.hasStatement(subject, null, null, false);
        }
    }

    public List<String> typesOf(IRI subject) {
        requireProjectOpen();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            return readTypes(conn, subject);
        }
    }

    /**
     * Entities whose name (any of {@link #LABEL_PREDICATES}) matches {@code text}, best first.
     * Case- and accent-insensitive, and tolerant of small spelling differences
     * ("dupond" finds "Dupont"). Only data graphs are searched: not the RiC-O ontology graph,
     * not the metadata graphs.
     *
     * @param typeIri optional; when set, only entities of that type or one of its subtypes
     */
    public List<EntityMatchDto> searchByLabel(String text, String typeIri, int limit) {
        requireProjectOpen();
        Set<String> queryWords = new LinkedHashSet<>(TextNormalizer.tokens(text));
        if (queryWords.isEmpty()) return List.of();
        String foldedQuery = String.join(" ", queryWords);

        StringBuilder values = new StringBuilder();
        for (String p : LABEL_PREDICATES) values.append('<').append(p).append("> ");
        String typeFilter = "";
        if (typeIri != null && !typeIri.isBlank()) {
            StringBuilder types = new StringBuilder();
            for (String t : ontologyService.expandWithSubtypes(expand(typeIri))) types.append('<').append(t).append("> ");
            typeFilter = "?s a ?t . VALUES ?t { " + types + "}";
        }
        String sparql = "SELECT ?s ?label WHERE { VALUES ?p { " + values + "} "
                + "GRAPH ?g { ?s ?p ?label } FILTER(isLiteral(?label) && isIRI(?s)) "
                + "FILTER(?g != <" + RdfContexts.CTX_META + "> && ?g != <" + RdfContexts.CTX_ONTO_RICO + ">) "
                + "FILTER(!STRSTARTS(STR(?g), \"" + RdfNamespaces.APP + "/projects#\")) "
                + typeFilter + " } LIMIT 50000";

        Map<IRI, Double> bestScore = new LinkedHashMap<>();
        Map<IRI, String> bestLabelOf = new HashMap<>();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql);
            q.setMaxExecutionTime(10);
            try (TupleQueryResult res = q.evaluate()) {
                while (res.hasNext()) {
                    BindingSet bs = res.next();
                    IRI s = (IRI) bs.getValue("s");
                    String label = bs.getValue("label").stringValue();
                    double score = labelScore(foldedQuery, queryWords, label);
                    if (score < 0.75) continue;
                    if (score > bestScore.getOrDefault(s, 0.0)) {
                        bestScore.put(s, score);
                        bestLabelOf.put(s, label);
                    }
                }
            }

            List<IRI> ranked = new ArrayList<>(bestScore.keySet());
            ranked.sort((a, b) -> Double.compare(bestScore.get(b), bestScore.get(a)));
            List<EntityMatchDto> out = new ArrayList<>();
            IRI internal = internalCtx();
            for (IRI s : ranked.subList(0, Math.min(limit, ranked.size()))) {
                EntityMatchDto m = new EntityMatchDto();
                m.iri = s.stringValue();
                m.label = bestLabelOf.get(s);
                m.types = readTypes(conn, s);
                boolean isInternal = conn.hasStatement(s, null, null, false, internal);
                m.source = isInternal ? "internal" : "external";
                if (!isInternal) {
                    try (var st = conn.getStatements(s, null, null)) {
                        while (st.hasNext() && m.datasourceShortName == null) {
                            Resource ctx = st.next().getContext();
                            if (ctx instanceof IRI g && !g.stringValue().equals(RdfContexts.CTX_META)) {
                                m.datasourceShortName = dsService.getShortNameByGraph(g);
                            }
                        }
                    }
                }
                m.score = Math.round(bestScore.get(s) * 100) / 100.0;
                out.add(m);
            }
            return out;
        }
    }

    /** Label, types and source of one existing entity, read from the store; null if it does not exist. */
    public EntityMatchDto matchOf(IRI subject) {
        requireProjectOpen();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(subject, null, null, false)) return null;
            EntityMatchDto m = new EntityMatchDto();
            m.iri = subject.stringValue();
            m.label = bestLabel(conn, subject);
            m.types = readTypes(conn, subject);
            boolean isInternal = conn.hasStatement(subject, null, null, false, internalCtx());
            m.source = isInternal ? "internal" : "external";
            if (!isInternal) {
                try (var st = conn.getStatements(subject, null, null)) {
                    while (st.hasNext() && m.datasourceShortName == null) {
                        Resource ctx = st.next().getContext();
                        if (ctx instanceof IRI g && !g.stringValue().equals(RdfContexts.CTX_META)) {
                            m.datasourceShortName = dsService.getShortNameByGraph(g);
                        }
                    }
                }
            }
            m.score = 1.0;
            return m;
        }
    }

    /** 1 for the same words, 0.9 when every query word appears, else a discounted mean best-word similarity. */
    private static double labelScore(String foldedQuery, Set<String> queryWords, String label) {
        List<String> labelWords = TextNormalizer.tokens(label);
        if (labelWords.isEmpty()) return 0;
        if (String.join(" ", labelWords).equals(foldedQuery)) return 1.0;
        if (labelWords.containsAll(queryWords)) return 0.9;
        double sum = 0;
        for (String q : queryWords) {
            double best = 0;
            for (String w : labelWords) best = Math.max(best, TextNormalizer.similarity(q, w));
            sum += best;
        }
        return (sum / queryWords.size()) * 0.85;
    }

}