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
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class RdfEntityService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private final ProjectService projectService;
    private final DataSourceService dsService;
    private final OntologyService ontologyService;

    public RdfEntityService(ProjectService projectService, DataSourceService dsService, OntologyService ontologyService) {
        this.projectService = projectService;
        this.dsService = dsService;
        this.ontologyService = ontologyService;
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
        String entityTypeName = entityIri.replace(RdfNamespaces.RICO,"");
        ProjectDto currentProject = this.projectService.readCurrentProject();
        return vf.createIRI(currentProject.prefix + '/' + entityTypeName +'/' + key);
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

        // 0) rico:name (TOP PRIORITY)
        IRI ricoName = vf.createIRI("https://www.ica.org/standards/RiC/ontology#name");
        try (var st = conn.getStatements(subject, ricoName, null)) {
            if (st.hasNext()) return st.next().getObject().stringValue();
        }

        // 1) rdfs:label
        try (var st = conn.getStatements(subject, RDFS.LABEL, null)) {
            if (st.hasNext()) return st.next().getObject().stringValue();
        }

        // 2) dcterms:title
        IRI title = vf.createIRI("http://purl.org/dc/terms/title");
        try (var st = conn.getStatements(subject, title, null)) {
            if (st.hasNext()) return st.next().getObject().stringValue();
        }

        // 3) foaf:name
        IRI name = vf.createIRI("http://xmlns.com/foaf/0.1/name");
        try (var st = conn.getStatements(subject, name, null)) {
            if (st.hasNext()) return st.next().getObject().stringValue();
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

        String entityKey = UUID.randomUUID().toString();
        IRI subject = iriFromKey(req.types.get(0),entityKey);


        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            conn.begin();
            IRI CTX_INTERNAL = internalCtx();

            // rdf:type
            for (String t : req.types) {
                IRI typeIri = vf.createIRI(expand(t));
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
            touchInternalDataSource(conn);
            conn.commit();
        }

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
    //  READ LIST (vue tableau)
    public List<RdfEntitySummaryDto> listByType(String typeCurieOrIri) {

        requireProjectOpen();
        if (typeCurieOrIri == null || typeCurieOrIri.isBlank()) {
            throw new BadRequestException("Type parameter is mandatory.");
        }

        IRI typeIri = vf.createIRI(expand(typeCurieOrIri));
        List<RdfEntitySummaryDto> out = new ArrayList<>();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            try (var stmts = conn.getStatements(null, RDF.TYPE, typeIri)) {
                while (stmts.hasNext()) {

                    Statement st = stmts.next();
                    Resource s = st.getSubject();
                    if (!s.isIRI()) continue;

                    IRI subject = (IRI) s;
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

        // Tri simple par label
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

            for (String t : readTypes(conn, subject)) {
                dto.types.add(t);
            }

            Map<String, RdfPropertyDto> byPredicate = new LinkedHashMap<>();
            // cache graph -> shortName resolution within this call, avoid re-querying per triple
            Map<Resource, String> shortNameCache = new HashMap<>();

            try (var stmts = conn.getStatements(subject, null, null)) {
                while (stmts.hasNext()) {
                    Statement st = stmts.next();
                    IRI pred = st.getPredicate();
                    Value obj = st.getObject();
                    Resource ctx = st.getContext();

                    if (pred.equals(RDF.TYPE)) continue;

                    String predKey = pred.stringValue();
                    RdfPropertyDto p = byPredicate.computeIfAbsent(predKey, k -> {
                        RdfPropertyDto newP = new RdfPropertyDto();
                        newP.predicate = predKey;
                        newP.schema = ontologyService.getPropertyByUri(predKey);
                        return newP;
                    });

                    RdfValueDto v = new RdfValueDto();

                    // --- provenance for this specific triple ---
                    boolean fromInternal = ctx != null && ctx.equals(CTX_INTERNAL);
                    v.source = fromInternal ? "internal" : "external";

                    if (fromInternal) {
                        v.datasourceShortName = "internal"; // or projectName + "_internal", your convention
                    } else if (ctx instanceof IRI) {
                        v.datasourceShortName = shortNameCache.computeIfAbsent(
                                ctx, c -> dsService.getShortNameByGraph((IRI) c));
                    }
                    // --- end provenance ---

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

            dto.properties.addAll(byPredicate.values());
            return dto;
        }
    }
    //  UPDATE
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

            touchInternalDataSource(conn);

            conn.commit();
        }

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
            touchInternalDataSource(conn);

            conn.commit();
        }
    }


    private void touchInternalDataSource(RepositoryConnection conn) {
        IRI ctxMeta = vf.createIRI(RdfContexts.CTX_META);
        String projectName = projectService.readCurrentProject().name;
        IRI ds = vf.createIRI(RdfNamespaces.APP + "/datasource/" + projectName + "_internal");

        String now = OffsetDateTime.now().toString();
        conn.remove(ds, vf.createIRI("http://purl.org/dc/terms/modified"), null, ctxMeta);
        conn.add(ds, vf.createIRI("http://purl.org/dc/terms/modified"), vf.createLiteral(now), ctxMeta);
    }

}