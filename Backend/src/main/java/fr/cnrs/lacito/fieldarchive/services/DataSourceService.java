package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.CreateInternalDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.dtos.CreateExternalDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.dtos.DataSourceDto;
import fr.cnrs.lacito.fieldarchive.dtos.UpdateDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;


@Service
public class DataSourceService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    // --- Helpers IRI ---
    private IRI metaCtx() {
        return vf.createIRI(RdfContexts.CTX_META);
    }

    private IRI dsIri(String shortName) {
        return vf.createIRI(RdfNamespaces.APP + "datasource/" + shortName);
    }

    /** Graphe nommé (contexte) où seront stockés les triplets "contenu" de la source */
    private IRI graphCtx(String shortName) {
        // identifiant simple et stable
        return vf.createIRI("urn:datasource:" + shortName);
    }

    private IRI typeInternalDataSource() {
        return vf.createIRI(RdfNamespaces.APP, "InternalDataSource");
    }

    private IRI pShortName() {
        return vf.createIRI(RdfNamespaces.APP, "shortName");
    }

    private IRI pEditable() {
        return vf.createIRI(RdfNamespaces.APP, "editable");
    }

    private IRI pGraph() {
        return vf.createIRI(RdfNamespaces.APP, "graph");
    }

    // --- nouveaux prédicats RDF pour les sources ---
    private IRI pSourceType() {
        return vf.createIRI(RdfNamespaces.APP, "sourceType");
    }

    private IRI pLastSync() {
        return vf.createIRI(RdfNamespaces.APP, "lastSync");
    }

    private IRI pSourceTool() {
        return vf.createIRI(RdfNamespaces.APP, "sourceTool");
    }

    private IRI pSourceLocation() {
        return vf.createIRI(RdfNamespaces.APP, "sourceLocation");
    }


    private void requireProjectOpen() {
        if (!ProjectContext.isOpen()) {
            throw new BadRequestException("Aucun projet ouvert.");
        }
    }

    private void validateShortName(String shortName) {
        if (shortName == null || shortName.isBlank()) {
            throw new BadRequestException("shortName obligatoire (ex: internal).");
        }
        // option : vous pouvez imposer un pattern (sans espaces, etc.)
        if (shortName.contains(" ")) {
            throw new BadRequestException("shortName ne doit pas contenir d'espaces.");
        }
    }

    // =========================================================
    // CREATE (Source interne)
    // =========================================================
    public void createInternalDataSource(CreateInternalDataSourceRequest req) {
        // Vérifier qu’un projet est ouvert
        requireProjectOpen();

        // Vérifier que le shortName est valide
        validateShortName(req.getShortName());

        // 3 Préparer les identifiants RDF
        IRI ctxMeta = metaCtx();                         // graphe des métadonnées
        IRI ds = dsIri(req.getShortName());              // URI de la source
        IRI ctxGraph = graphCtx(req.getShortName());     // graphe nommé de la source

        // 4 Ouvrir une connexion au repository du projet
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            // Unicité: empêcher de recréer la même source
            if (conn.hasStatement(ds, RDF.TYPE, null, false, ctxMeta)) {
                throw new BadRequestException("DataSource existe déjà: " + req.getShortName());
            }
            // Démarrer une transaction RDF
            conn.begin();

            // Métadonnées (dans CTX_META)
            // ===== Métadonnées de la source =====
            // Dire que c’est une source interne
            conn.add(ds, RDF.TYPE, typeInternalDataSource(), ctxMeta);


            // shortName (identifiant fonctionnel)
            conn.add(ds, pShortName(), vf.createLiteral(req.getShortName()), ctxMeta);

            // Nom lisible
            if (req.getName() != null && !req.getName().isBlank()) {
                conn.add(ds, RDFS.LABEL, vf.createLiteral(req.getName()), ctxMeta);
            }
            // Description (optionnelle)
            if (req.getDescription() != null && !req.getDescription().isBlank()) {
                conn.add(ds, DCTERMS.DESCRIPTION, vf.createLiteral(req.getDescription()), ctxMeta);
            }


            // Type de source = internal
            conn.add(ds, pSourceType(), vf.createLiteral("internal"), ctxMeta);
            // Editable = true
            conn.add(ds, pEditable(), vf.createLiteral(true), ctxMeta);
            String now = OffsetDateTime.now().toString();

            // Date de création
            conn.add(ds, DCTERMS.CREATED, vf.createLiteral(now), ctxMeta);
            // Dernière synchronisation
            conn.add(ds, pLastSync(), vf.createLiteral(now), ctxMeta);

            // Lien vers le graphe nommé de la source (où le contenu sera stocké)
            conn.add(ds, pGraph(), ctxGraph, ctxMeta);
            // Valider la transaction
            conn.commit();
        }
    }

    // =========================================================
    // READ ALL
    // =========================================================
    public List<DataSourceDto> listDataSources() {

        requireProjectOpen();
        IRI ctxMeta = metaCtx();
        List<DataSourceDto> out = new ArrayList<>();

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            // récupérer TOUTES les ressources ayant un sourceType
            try (var stmts = conn.getStatements(
                    null,
                    pSourceType(),
                    null,
                    ctxMeta)) {

                while (stmts.hasNext()) {
                    Statement st = stmts.next();
                    IRI ds = (IRI) st.getSubject();
                    out.add(readOne(conn, ds, ctxMeta));
                }
            }
        }
        return out;
    }


    // =========================================================
    // READ ONE by shortName
    // =========================================================
    public DataSourceDto getDataSource(String shortName) {
        requireProjectOpen();
        validateShortName(shortName);

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(shortName);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ds, pSourceType(), null, false, ctxMeta)) {
                throw new NotFoundException("DataSource introuvable: " + shortName);
            }
            return readOne(conn, ds, ctxMeta);
        }
    }
    private String getLiteral(RepositoryConnection conn, IRI subject, IRI predicate, IRI ctx) {
        try (var stmts = conn.getStatements(subject, predicate, null, ctx)) {
            if (stmts.hasNext()) {
                Value v = stmts.next().getObject();
                if (v.isLiteral()) {
                    return v.stringValue();
                }
            }
            return null;
        }
    }

    private String getIri(RepositoryConnection conn, IRI subject, IRI predicate, IRI ctx) {
        try (var stmts = conn.getStatements(subject, predicate, null, ctx)) {
            if (stmts.hasNext()) {
                Value v = stmts.next().getObject();
                if (v.isIRI()) {
                    return v.stringValue();
                }
            }
            return null;
        }
    }

    private DataSourceDto readOne(RepositoryConnection conn, IRI ds, IRI ctxMeta) {
        DataSourceDto dto = new DataSourceDto();
        dto.id = ds.stringValue();

        // shortName
        dto.shortName = getLiteral(conn, ds, pShortName(), ctxMeta);

        // name
        dto.longName = getLiteral(conn, ds, RDFS.LABEL, ctxMeta);

        // description
        dto.description = getLiteral(conn, ds, DCTERMS.DESCRIPTION, ctxMeta);

        // editable
        String editable = getLiteral(conn, ds, pEditable(), ctxMeta);
        dto.editable = editable != null && Boolean.parseBoolean(editable);

        // type (internal / external)
        dto.type = getLiteral(conn, ds, pSourceType(), ctxMeta);

        // createdAt
        dto.createdAt = getLiteral(conn, ds, DCTERMS.CREATED, ctxMeta);

        // lastSync
        dto.lastSync = getLiteral(conn, ds, pLastSync(), ctxMeta);

        // graph IRI
        dto.graphIri = getIri(conn, ds, pGraph(), ctxMeta);

        // external-only (null for internal, c’est NORMAL)
        dto.sourceTool = getLiteral(conn, ds, pSourceTool(), ctxMeta);
        dto.sourceLocation = getLiteral(conn, ds, pSourceLocation(), ctxMeta);

        return dto;
    }

    // =========================================================
    // UPDATE (métadonnées seulement)
    // =========================================================
    public void updateDataSource(String shortName, UpdateDataSourceRequest req) {
        requireProjectOpen();
        validateShortName(shortName);

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(shortName);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ds, pSourceType(), null, false, ctxMeta)) {
                throw new NotFoundException("DataSource introuvable: " + shortName);
            }

            conn.begin();

            // longName -> RDFS.LABEL
            if (req.getLongName() != null) {
                conn.remove(ds, RDFS.LABEL, null, ctxMeta);
                if (!req.getLongName().isBlank()) {
                    conn.add(ds, RDFS.LABEL, vf.createLiteral(req.getLongName()), ctxMeta);
                }
            }

            // description -> DCTERMS.DESCRIPTION
            if (req.getDescription() != null) {
                conn.remove(ds, DCTERMS.DESCRIPTION, null, ctxMeta);
                if (!req.getDescription().isBlank()) {
                    conn.add(ds, DCTERMS.DESCRIPTION, vf.createLiteral(req.getDescription()), ctxMeta);
                }
            }
            // Mise à jour automatique de la date de modification
            String now = OffsetDateTime.now().toString();
            conn.remove(ds, pLastSync(), null, ctxMeta);
            conn.add(ds, pLastSync(), vf.createLiteral(now), ctxMeta);

            conn.commit();
        }
    }

    // =========================================================
    // DELETE (métadonnées + graphe contenu)
    // =========================================================
    public void deleteDataSource(String shortName) {
        requireProjectOpen();
        validateShortName(shortName);
        // RÈGLE MÉTIER : la source interne ne peut pas être supprimée
        if ("internal".equalsIgnoreCase(shortName)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La source interne ne peut pas être supprimée"
            );
        }
        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(shortName);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ds, pSourceType(), null, false, ctxMeta)) {
                throw new NotFoundException("DataSource introuvable: " + shortName);
            }

            // retrouver le graphe associé
            var gSt = conn.getStatements(ds, pGraph(), null, ctxMeta).stream().findFirst().orElse(null);
            IRI ctxGraph = (gSt != null && gSt.getObject() instanceof IRI) ? (IRI) gSt.getObject() : null;

            conn.begin();

            // supprimer tout le contenu du graphe (si existe)
            if (ctxGraph != null) {
                conn.clear(ctxGraph);
            }

            // supprimer toutes les métadonnées de la source
            conn.remove(ds, null, null, ctxMeta);
            conn.remove((Resource) null, null, ds, ctxMeta); // au cas où la source est objet

            conn.commit();
        }
    }

    private IRI typeExternalDataSource() {
        return vf.createIRI(RdfNamespaces.APP, "ExternalDataSource");
    }

    public void createExternalDataSource(CreateExternalDataSourceRequest req) {

        requireProjectOpen();
        validateShortName(req.getShortName());

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(req.getShortName());
        IRI ctxGraph = graphCtx(req.getShortName());

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            if (conn.hasStatement(ds, RDF.TYPE, null, false, ctxMeta)) {
                throw new BadRequestException(
                        "DataSource déjà existante : " + req.getShortName()
                );
            }

            conn.begin();

            // type RDF
            conn.add(ds, RDF.TYPE, typeExternalDataSource(), ctxMeta);

            // métadonnées communes
            conn.add(ds, pShortName(),
                    vf.createLiteral(req.getShortName()), ctxMeta);

            if (req.getName() != null && !req.getName().isBlank()) {
                conn.add(ds, RDFS.LABEL,
                        vf.createLiteral(req.getName()), ctxMeta);
            }

            if (req.getDescription() != null && !req.getDescription().isBlank()) {
                conn.add(ds, DCTERMS.DESCRIPTION,
                        vf.createLiteral(req.getDescription()), ctxMeta);
            }

            // spécifique externe
            conn.add(ds, pSourceType(),
                    vf.createLiteral("external"), ctxMeta);

            conn.add(ds, pEditable(),
                    vf.createLiteral(false), ctxMeta);

            String now = OffsetDateTime.now().toString();
            conn.add(ds, DCTERMS.CREATED, vf.createLiteral(now), ctxMeta);
            conn.add(ds, pLastSync(), vf.createLiteral(now), ctxMeta);

            conn.add(ds, pSourceTool(),
                    vf.createLiteral(req.getSourceTool()), ctxMeta);

            conn.add(ds, pSourceLocation(),
                    vf.createLiteral(req.getSourceLocation()), ctxMeta);

            // lien vers le graphe
            conn.add(ds, pGraph(), ctxGraph, ctxMeta);

            conn.commit();
        }
    }

    public void synchronizeExternalDataSource(String shortName) {

        requireProjectOpen();
        validateShortName(shortName);

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(shortName);

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            // 1 Vérifier que la source existe
            if (!conn.hasStatement(ds, pSourceType(), null, false, ctxMeta)) {
                throw new NotFoundException("Source introuvable : " + shortName);
            }

            // 2 Vérifier que c’est une source externe
            String type = getLiteral(conn, ds, pSourceType(), ctxMeta);
            if (!"external".equals(type)) {
                throw new BadRequestException(
                        "La synchronisation est réservée aux sources externes"
                );
            }

            // 3 Récupérer le graphe et le fichier
            String graphIri = getIri(conn, ds, pGraph(), ctxMeta);
            String filePath = getLiteral(conn, ds, pSourceLocation(), ctxMeta);

            if (graphIri == null || filePath == null) {
                throw new BadRequestException(
                        "Graphe ou chemin du fichier manquant"
                );
            }

            IRI ctxGraph = vf.createIRI(graphIri);

            // 4 Transaction RDF
            conn.begin();

            // 5 Vider le graphe
            conn.clear(ctxGraph);

            // 6 Lire le fichier RDF
            /*Path path = Path.of(filePath);

            if (!Files.exists(path)) {
                throw new BadRequestException(
                        "Fichier RDF introuvable : " + filePath
                );
            }*/

            String cleanPath = filePath;
            if (cleanPath.startsWith("file:///")) {
                cleanPath = cleanPath.substring(8);  // Enlève "file:///"
            } else if (cleanPath.startsWith("file://")) {
                cleanPath = cleanPath.substring(7);   // Enlève "file://"
            }

            System.out.println("📍 Original path: " + filePath);
            System.out.println("📂 Clean path: " + cleanPath);

            Path path = Path.of(cleanPath);

            System.out.println("✅ Absolute path: " + path.toAbsolutePath());

            if (!Files.exists(path)) {
                throw new BadRequestException(
                        "Fichier RDF introuvable : " + path.toAbsolutePath()
                );
            }

            try (InputStream in = Files.newInputStream(path)) {
                conn.add(in, "", RDFFormat.TURTLE, ctxGraph);
                long tripleCount = conn.size(ctxGraph);
                System.out.println("📊 Imported " + tripleCount + " triples into graph: " + ctxGraph);
            }

            // 7 Mettre à jour lastSync
            String now = OffsetDateTime.now().toString();
            conn.remove(ds, pLastSync(), null, ctxMeta);
            conn.add(ds, pLastSync(), vf.createLiteral(now), ctxMeta);

            // 8 Commit
            conn.commit();
        }
        catch (Exception e) {
            throw new RuntimeException("Erreur lors de la synchronisation", e);
        }
    }

}