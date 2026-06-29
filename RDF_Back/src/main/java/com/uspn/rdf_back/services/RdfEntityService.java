package com.uspn.rdf_back.services;

import com.uspn.rdf_back.core.ProjectContext;
import com.uspn.rdf_back.core.RdfNamespaces;
import com.uspn.rdf_back.dtos.*;
import com.uspn.rdf_back.exception.BadRequestException;
import com.uspn.rdf_back.exception.NotFoundException;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class RdfEntityService {
    private static final String ENTITY_NS = RdfNamespaces.APP + "entity/";

    private IRI iriFromKey(String key) {
        return vf.createIRI(ENTITY_NS + key);
    }

    private String keyFromIri(IRI iri) {
        String s = iri.stringValue();
        if (!s.startsWith(ENTITY_NS)) {
            throw new IllegalArgumentException("IRI invalide: " + s);
        }
        return s.substring(ENTITY_NS.length());
    }

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    // Graphe interne éditable (aligné avec ton implémentation actuelle)
    private static final IRI CTX_INTERNAL = vf.createIRI("urn:datasource:internal");

    // Prefixes acceptés par l’API (CURIE -> IRI)
    private static final Map<String, String> PREFIX = Map.of(
            "app", RdfNamespaces.APP,
            "ric", RdfNamespaces.RICO,
            "rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
            "xsd", "http://www.w3.org/2001/XMLSchema#",
            "dcterms", "http://purl.org/dc/terms/",
            "foaf", "http://xmlns.com/foaf/0.1/"
    );

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
        // 1) rdfs:label si présent
        try (var st = conn.getStatements(subject, RDFS.LABEL, null)) {
            if (st.hasNext()) return st.next().getObject().stringValue();
        }
        // 2) sinon, première valeur littérale trouvée
        try (var st = conn.getStatements(subject, null, null)) {
            while (st.hasNext()) {
                Value o = st.next().getObject();
                if (o.isLiteral()) return o.stringValue();
            }
        }
        // 3) sinon, fallback: fragment/IRI
        return subject.stringValue();
    }

    // =========================
    //  API TYPES
    // =========================
    public List<String> listTypes() {
        // MVP : on renvoie un set de types “courants”
        // (tu peux plus tard les détecter via l’ontologie chargée)
        return List.of(
                "ric:Event",
                "ric:Person",
                "ric:Record",
                "ric:Place",
                "foaf:Person"
        );
    }

    // =========================
    //  CREATE
    // =========================
    public RdfEntityDto create(CreateRdfEntityRequest req) {
        requireProjectOpen();
        if (req == null) throw new BadRequestException("Body manquant.");
        if (req.types == null || req.types.isEmpty()) {
            throw new BadRequestException("types obligatoire (au moins 1 rdf:type).");
        }

        String entityKey = UUID.randomUUID().toString();
        IRI subject = iriFromKey(entityKey);


        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            conn.begin();

            // rdf:type
            for (String t : req.types) {
                IRI typeIri = vf.createIRI(expand(t));
                conn.add(subject, RDF.TYPE, typeIri, CTX_INTERNAL);
            }

            // properties
            if (req.properties != null) {
                for (RdfPropertyDto p : req.properties) {
                    addProperty(conn, subject, p);
                }
            }
            touchInternalDataSource(conn);
            conn.commit();
        }

        return getByKey(entityKey);

    }

    private void addProperty(RepositoryConnection conn, IRI subject, RdfPropertyDto p) {
        if (p == null) return;
        if (p.predicate == null || p.predicate.isBlank()) {
            throw new BadRequestException("Predicate is mandatory!");
        }
        if (p.kind == null || p.kind.isBlank()) {
            throw new BadRequestException("Predicate's kind is mandatory (literal|iri).");
        }

        IRI pred = vf.createIRI(expand(p.predicate));

        if ("iri".equalsIgnoreCase(p.kind)) {
            if (p.value == null || p.value.isBlank()) throw new BadRequestException("value obligatoire pour kind=iri.");
            IRI obj = vf.createIRI(expand(p.value));
            conn.add(subject, pred, obj, CTX_INTERNAL);
            return;
        }

        if (!"literal".equalsIgnoreCase(p.kind)) {
            throw new BadRequestException("kind invalide: " + p.kind);
        }

        if (p.value == null) throw new BadRequestException("value obligatoire pour kind=literal.");

        Literal lit;
        if (p.lang != null && !p.lang.isBlank()) {
            lit = vf.createLiteral(p.value, p.lang.trim());
        } else if (p.datatype != null && !p.datatype.isBlank()) {
            IRI dt = vf.createIRI(expand(p.datatype));
            lit = vf.createLiteral(p.value, dt);
        } else {
            lit = vf.createLiteral(p.value);
        }

        conn.add(subject, pred, lit, CTX_INTERNAL);
    }


    //  READ LIST (vue tableau)
    public List<RdfEntitySummaryDto> listByType(String typeCurieOrIri) {

        requireProjectOpen();
        if (typeCurieOrIri == null || typeCurieOrIri.isBlank()) {
            throw new BadRequestException("Paramètre type obligatoire.");
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

                    out.add(dto);
                }
            }
        }

        // Tri simple par label
        out.sort(Comparator.comparing(a -> a.label == null ? "" : a.label));
        return out;
    }
    // =========================
    //  READ ONE
    // =========================
    public RdfEntityDto getByKey(String key) {
        requireProjectOpen();
        if (key == null || key.isBlank()) {
            throw new BadRequestException("entityKey manquant.");
        }
        IRI subject = iriFromKey(key);
        return getByIri(subject);
    }

    public RdfEntityDto getByIri(IRI subject) {
        requireProjectOpen();
        if (subject == null) {
            throw new BadRequestException("IRI manquant.");
        }

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            // vérifier existence (au moins un statement sur ce sujet dans le repo)
            boolean exists;
            try (var st = conn.getStatements(subject, null, null)) {
                exists = st.hasNext();
            }
            if (!exists) throw new NotFoundException("Entité introuvable: " + subject.stringValue());
            // Déterminer si elle est interne ou externe
            boolean internal = isInternalEntity(conn, subject);
            // Construire le DTO
            RdfEntityDto dto = new RdfEntityDto();
            dto.entityKey = keyFromIri(subject);
            dto.iri = subject.stringValue();
            dto.source = internal ? "internal" : "external";
            dto.editable = internal;

            // Lire les rdf:type
            for (String t : readTypes(conn, subject)) {
                dto.types.add(t);
            }

            // properties (on renvoie tout ce qu’on trouve)
            // Lire toutes les propriétés
            try (var stmts = conn.getStatements(subject, null, null, CTX_INTERNAL)) {
                while (stmts.hasNext()) {
                    Statement st = stmts.next();
                    IRI pred = st.getPredicate();
                    Value obj = st.getObject();

                    // Ignorer rdf:type (déjà traité)
                    if (pred.equals(RDF.TYPE)) continue;

                    RdfPropertyDto p = new RdfPropertyDto();
                    p.predicate = pred.stringValue();

                    if (obj.isIRI()) {
                        p.kind = "iri";
                        p.value = obj.stringValue();

                    } else if (obj.isLiteral()) {
                        Literal lit = (Literal) obj;
                        p.kind = "literal";
                        p.value = lit.getLabel();
                        p.datatype = lit.getDatatype() != null
                                ? lit.getDatatype().stringValue()
                                : null;
                        p.lang = lit.getLanguage().orElse(null);

                    } else {
                        // blank node ou autre (rare)
                        p.kind = "other";
                        p.value = obj.stringValue();
                    }

                    dto.properties.add(p);
                }
            }

            return dto;
        }
    }

    // =========================
    //  UPDATE
    // =========================
    // =========================
//  UPDATE (interne uniquement)
// =========================
    public RdfEntityDto updateByKey(String key, UpdateRdfEntityRequest req) {

        requireProjectOpen();

        if (key == null || key.isBlank()) {
            throw new BadRequestException("entityKey manquant.");
        }
        if (req == null) {
            throw new BadRequestException("Body manquant.");
        }

        IRI subject = iriFromKey(key);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            // 1 Vérifier existence
            boolean exists;
            try (var st = conn.getStatements(subject, null, null)) {
                exists = st.hasNext();
            }
            if (!exists) {
                throw new NotFoundException("Entité introuvable: " + key);
            }

            // 2 Vérifier éditable
            if (!isInternalEntity(conn, subject)) {
                throw new BadRequestException(
                        "Modification interdite : entité provenant d'une source externe."
                );
            }

            conn.begin();

            // 3 Remplacer uniquement les prédicats fournis
            if (req.properties != null) {
                for (RdfPropertyDto p : req.properties) {

                    if (p == null || p.predicate == null || p.predicate.isBlank()) {
                        continue;
                    }

                    IRI pred = vf.createIRI(expand(p.predicate));

                    // Supprimer anciennes valeurs dans le graphe interne
                    conn.remove(subject, pred, null, CTX_INTERNAL);

                    // Ajouter la nouvelle valeur si présente
                    if (p.value != null && !p.value.isBlank()) {
                        addProperty(conn, subject, p);
                    }
                }
            }

            // 4 Mettre à jour lastSync de la source interne
            touchInternalDataSource(conn);

            conn.commit();
        }

        return getByKey(key);
    }

    //  DELETE (interne uniquement)
// =========================
    public void deleteByKey(String key) {

        requireProjectOpen();

        if (key == null || key.isBlank()) {
            throw new BadRequestException("entityKey manquant.");
        }

        IRI subject = iriFromKey(key);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            // 1 Vérifier existence
            boolean exists;
            try (var st = conn.getStatements(subject, null, null)) {
                exists = st.hasNext();
            }
            if (!exists) {
                throw new NotFoundException("Entité introuvable: " + key);
            }

            // 2 Vérifier éditable
            if (!isInternalEntity(conn, subject)) {
                throw new BadRequestException(
                        "Suppression interdite : entité provenant d'une source externe."
                );
            }

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
        IRI ctxMeta = vf.createIRI("urn:datasource:meta");
        IRI ds = vf.createIRI("http://uspn.fr/app#datasource/internal");

        String now = OffsetDateTime.now().toString();

        conn.remove(ds, vf.createIRI("http://purl.org/dc/terms/modified"), null, ctxMeta);
        conn.add(ds,
                vf.createIRI("http://purl.org/dc/terms/modified"),
                vf.createLiteral(now),
                ctxMeta);
    }

}