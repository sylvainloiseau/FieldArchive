package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import fr.cnrs.lacito.fieldarchive.exceptions.ImportException;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.OffsetDateTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


@Service
public class DataSourceService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    // --- Helpers IRI ---
    private IRI metaCtx() {
        return vf.createIRI(RdfContexts.CTX_META);
    }

    private IRI dsIri(String shortName) {
        return vf.createIRI(RdfNamespaces.APP + "/datasource/" + shortName);
    }

    /** Graphe nommé (contexte) où seront stockés les triplets "contenu" de la source */
    private IRI graphCtx(String shortName) {
        // identifiant simple et stable
        return vf.createIRI("urn:datasource:" + shortName);
    }

    private IRI typeInternalDataSource() {
        return vf.createIRI(RdfNamespaces.APP, "InternalDataSource");
    }

    /**
     * Canonical form of a user-supplied file location: trimmed, without any {@code file://}
     * prefix. Returns null when nothing usable is left, so the write and read paths agree on
     * what "no location" means.
     */
    private String normalizeFileLocation(String raw) {
        if (raw == null) return null;
        String clean = raw.trim();
        if (clean.startsWith("file:///")) {
            clean = clean.substring(7);      // keep the leading '/' of the absolute path
        } else if (clean.startsWith("file://")) {
            clean = clean.substring(7);
        }
        clean = clean.trim();
        return clean.isEmpty() ? null : clean;
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

    private FileImportService fileImportService;
    private ProjectService projectService;

    public DataSourceService(@Lazy FileImportService fileImportService, ProjectService projectService){
        this.fileImportService = fileImportService;
        this.projectService = projectService;
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
            // sourceLocation -> app:sourceLocation (lets a user repoint a moved file)
            if (req.getSourceLocation() != null) {
                conn.remove(ds, pSourceLocation(), null, ctxMeta);
                String location = normalizeFileLocation(req.getSourceLocation());
                if (location != null) {
                    conn.add(ds, pSourceLocation(), vf.createLiteral(location), ctxMeta);
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
        String internalShortName = projectService.readCurrentProject().name + "_internal";
        if (internalShortName.equalsIgnoreCase(shortName)) {
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

    public void createExternalDataSource(CreateExternalDataSourceRequest req, MultipartFile file) {

        requireProjectOpen();
        validateShortName(req.getShortName());

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(req.getShortName());
        IRI ctxGraph = graphCtx(req.getShortName());

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            if (conn.hasStatement(ds, RDF.TYPE, null, false, ctxMeta)) {
                throw new BadRequestException("DataSource already exists : " + req.getShortName());
            }

            conn.begin();

            conn.add(ds, RDF.TYPE, typeExternalDataSource(), ctxMeta);
            conn.add(ds, pShortName(), vf.createLiteral(req.getShortName()), ctxMeta);

            if (req.getName() != null && !req.getName().isBlank()) {
                conn.add(ds, RDFS.LABEL, vf.createLiteral(req.getName()), ctxMeta);
            }
            if (req.getDescription() != null && !req.getDescription().isBlank()) {
                conn.add(ds, DCTERMS.DESCRIPTION, vf.createLiteral(req.getDescription()), ctxMeta);
            }

            conn.add(ds, pSourceType(), vf.createLiteral("external"), ctxMeta);
            conn.add(ds, pEditable(), vf.createLiteral(false), ctxMeta);

            // Persisted so later re-imports can re-read the file without asking again.
            // Absent is not fatal: the uploaded bytes are still imported below, the source
            // simply is not syncable by path until a location is supplied.
            String sourceLocation = normalizeFileLocation(req.getSourceLocation());
            if (sourceLocation != null) {
                conn.add(ds, pSourceLocation(), vf.createLiteral(sourceLocation), ctxMeta);
            }

            String now = OffsetDateTime.now().toString();
            conn.add(ds, DCTERMS.CREATED, vf.createLiteral(now), ctxMeta);
            conn.add(ds, pLastSync(), vf.createLiteral(now), ctxMeta);
            conn.add(ds, pGraph(), ctxGraph, ctxMeta);

            conn.commit(); // commit metadata BEFORE importing, so getGraphIri (or the overload below) works
        }

        // import happens in its own step, using the graph IRI we already know — no re-lookup needed
        if (file != null && !file.isEmpty()) {
            try (InputStream is = file.getInputStream()) {
                String baseURI = projectService.readCurrentProject().prefix;
                fileImportService.importTurtle(is, baseURI, ctxGraph);
            } catch (ImportException e) {
                throw new ImportException("Import failed: " + e.getMessage());
            } catch (IOException e) {
                throw new ImportException("Import failed: " + e.getMessage());
            }
        }
    }


    public String getShortNameByGraph(IRI graphIri) {
        requireProjectOpen();
        if (graphIri == null) return null;

        IRI ctxMeta = metaCtx();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            try (var stmts = conn.getStatements(null, pGraph(), graphIri, ctxMeta)) {
                if (stmts.hasNext()) {
                    Resource ds = stmts.next().getSubject();
                    if (ds instanceof IRI) {
                        return getLiteral(conn, (IRI) ds, pShortName(), ctxMeta);
                    }
                }
            }
        }
        return null;
    }


    /**
     * Replaces the content of an external source's named graph.
     *
     * <p>Two ways in: either the caller uploads the bytes ({@code uploadedFile}), which works
     * whatever machine the file sits on, or the source has an {@code app:sourceLocation}
     * recorded and the backend re-reads that path from its own filesystem.</p>
     *
     * @param uploadedFile      optional freshly uploaded RDF file; wins over the recorded path
     * @param newSourceLocation optional path to remember, so the next sync is one click
     */
    public void synchronizeExternalDataSource(String shortName,
                                              MultipartFile uploadedFile,
                                              String newSourceLocation) {

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

            // 3 Le graphe à remplacer
            String graphIri = getIri(conn, ds, pGraph(), ctxMeta);
            if (graphIri == null) {
                throw new BadRequestException(
                        "Graphe manquant pour la source : " + shortName
                );
            }
            IRI ctxGraph = vf.createIRI(graphIri);

            // 4a Bytes uploaded by the client: no filesystem access needed on this side
            if (uploadedFile != null && !uploadedFile.isEmpty()) {
                String location = normalizeFileLocation(newSourceLocation);
                String label = uploadedFile.getOriginalFilename() != null
                        ? uploadedFile.getOriginalFilename()
                        : "uploaded file";
                try (InputStream in = uploadedFile.getInputStream()) {
                    replaceGraphContent(conn, ds, ctxGraph, ctxMeta, in, location, label);
                } catch (IOException e) {
                    throw new BadRequestException(
                            "Unreadable uploaded file " + label + " : " + e.getMessage()
                    );
                }
                return;
            }

            // 4b Otherwise re-read the location recorded at creation / edition time
            String location = normalizeFileLocation(
                    getLiteral(conn, ds, pSourceLocation(), ctxMeta)
            );
            if (location == null) {
                throw new BadRequestException(
                        "No file location recorded for this source. " +
                        "Set its location or re-import by selecting the file."
                );
            }

            Path path = Path.of(location);
            if (!Files.exists(path)) {
                throw new BadRequestException(
                        "RDF file not found: " + path.toAbsolutePath()
                );
            }

            try (InputStream in = Files.newInputStream(path)) {
                replaceGraphContent(conn, ds, ctxGraph, ctxMeta, in, null,
                        path.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new BadRequestException(
                        "Unreadable RDF file " + path.toAbsolutePath() + " : " + e.getMessage()
                );
            }
        }
    }

    /**
     * Empties {@code ctxGraph} and re-fills it from {@code in}, then refreshes app:lastSync —
     * all in one transaction, so a parse failure rolls back instead of leaving the source empty.
     * When {@code newLocation} is non-null it is upserted as app:sourceLocation.
     */
    private void replaceGraphContent(RepositoryConnection conn,
                                     IRI ds,
                                     IRI ctxGraph,
                                     IRI ctxMeta,
                                     InputStream in,
                                     String newLocation,
                                     String fileLabel) {
        conn.begin();
        try {
            conn.clear(ctxGraph);
            conn.add(in, "", RDFFormat.TURTLE, ctxGraph);

            String now = OffsetDateTime.now().toString();
            conn.remove(ds, pLastSync(), null, ctxMeta);
            conn.add(ds, pLastSync(), vf.createLiteral(now), ctxMeta);

            if (newLocation != null) {
                conn.remove(ds, pSourceLocation(), null, ctxMeta);
                conn.add(ds, pSourceLocation(), vf.createLiteral(newLocation), ctxMeta);
            }

            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            // A malformed or unreadable file is the caller's problem, not a server fault:
            // report it as a 400 naming the file rather than a generic 500.
            throw new BadRequestException(
                    "Import failed for " + fileLabel + " : " + e.getMessage()
            );
        }
    }

    public IRI getGraphIri(String shortName) {
        requireProjectOpen();
        validateShortName(shortName);

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(shortName);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            String graphIri = getIri(conn, ds, pGraph(), ctxMeta);
            if (graphIri == null) {
                throw new NotFoundException("No graph found for source : " + shortName);
            }
            return vf.createIRI(graphIri);
        }
    }
}