package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.ConflictException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import fr.cnrs.lacito.fieldarchive.exception.ServerErrorException;
import fr.cnrs.lacito.fieldarchive.services.importer.GraphReplacingSink;
import fr.cnrs.lacito.fieldarchive.services.importer.ImportInput;
import fr.cnrs.lacito.fieldarchive.services.importer.ImportSettings;
import fr.cnrs.lacito.fieldarchive.services.importer.ImportWarningCollector;
import fr.cnrs.lacito.fieldarchive.services.importer.Importer;
import fr.cnrs.lacito.fieldarchive.services.importer.ImporterOrigin;
import fr.cnrs.lacito.fieldarchive.services.importer.ImporterRegistry;
import fr.cnrs.lacito.fieldarchive.services.importer.ImporterRegistry.Resolution;
import fr.cnrs.lacito.fieldarchive.services.importer.ImporterRegistry.Status;
import fr.cnrs.lacito.ricimporterplugin.OptionDescriptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.OffsetDateTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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

    /** Base IRI given to a plugin: the prefix of the IRIs it mints (fixed at the first import). */
    private IRI pImportBaseIri() {
        return vf.createIRI(RdfNamespaces.APP, "importBaseIri");
    }

    /** Plugin options, a JSON object literal (only the values differing from the defaults). */
    private IRI pImporterOptions() {
        return vf.createIRI(RdfNamespaces.APP, "importerOptions");
    }

    /** Summary of the last successful import, a JSON literal ({@link LastImportDto}). */
    private IRI pLastImport() {
        return vf.createIRI(RdfNamespaces.APP, "lastImport");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectService projectService;
    private final ImporterRegistry importers;
    private final ApplicationEventPublisher events;

    public DataSourceService(ProjectService projectService, ImporterRegistry importers,
                             ApplicationEventPublisher events){
        this.projectService = projectService;
        this.importers = importers;
        this.events = events;
    }

    private void publishDataChanged() {
        events.publishEvent(new ProjectDataChangedEvent(ProjectContext.getProjectName()));
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
        dto.tripleCount = dto.graphIri != null ? conn.size(vf.createIRI(dto.graphIri)) : 0;

        // external-only (null for internal, c’est NORMAL)
        dto.sourceTool = getLiteral(conn, ds, pSourceTool(), ctxMeta);
        dto.sourceLocation = getLiteral(conn, ds, pSourceLocation(), ctxMeta);

        if ("external".equals(dto.type)) {
            Resolution importer = importers.resolve(dto.sourceTool);
            dto.importer = importer.id();
            dto.importerStatus = importer.status().name();
            dto.importerOptions = readOptions(conn, ds, ctxMeta);
            dto.importBaseIri = getLiteral(conn, ds, pImportBaseIri(), ctxMeta);
            dto.importBaseIriDefault = defaultBaseIri(dto.shortName);
            dto.lastImport = readJson(getLiteral(conn, ds, pLastImport(), ctxMeta), LastImportDto.class);
        }
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

            // How the source is imported
            if (req.getImporter() != null) {
                String id = requestedImporterId(req.getImporter(), getLiteral(conn, ds, pSourceTool(), ctxMeta));
                replaceLiteral(conn, ds, pSourceTool(), id, ctxMeta);
            }
            if (req.getImporterOptions() != null) {
                String id = req.getImporter() != null ? req.getImporter() : getLiteral(conn, ds, pSourceTool(), ctxMeta);
                writeOptions(conn, ds, cleanOptions(req.getImporterOptions(), importers.resolve(id).importer()), ctxMeta);
            }
            if (req.getImportBaseIri() != null) {
                replaceLiteral(conn, ds, pImportBaseIri(), blankToNull(req.getImportBaseIri()), ctxMeta);
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
            IRI ctxGraph;
            try (var gSt = conn.getStatements(ds, pGraph(), null, ctxMeta)) {
                var obj = gSt.hasNext() ? gSt.next().getObject() : null;
                ctxGraph = (obj instanceof IRI) ? (IRI) obj : null;
            }

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
        publishDataChanged();
    }

    private IRI typeExternalDataSource() {
        return vf.createIRI(RdfNamespaces.APP, "ExternalDataSource");
    }

    /**
     * Creates an external source, then fills it: from the uploaded file, else from
     * {@code sourceLocation} (a file, or the directory a plugin reads). The metadata is committed
     * first, so a failed first import leaves the source in place, empty, and the error is thrown.
     *
     * @return the import result, or null when there was nothing to import yet
     */
    public ImportResult createExternalDataSource(CreateExternalDataSourceRequest req, MultipartFile file) {

        requireProjectOpen();
        validateShortName(req.getShortName());

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(req.getShortName());
        IRI ctxGraph = graphCtx(req.getShortName());
        Resolution importer = importers.resolve(requestedImporterId(req.getImporter(), null));
        String sourceLocation = normalizeFileLocation(req.getSourceLocation());

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
            conn.add(ds, pSourceTool(), vf.createLiteral(importer.id()), ctxMeta);
            if (importer.status() == Status.PLUGIN) {
                writeOptions(conn, ds, cleanOptions(req.getImporterOptions(), importer.importer()), ctxMeta);
                replaceLiteral(conn, ds, pImportBaseIri(), blankToNull(req.getImportBaseIri()), ctxMeta);
            }

            // Persisted so later re-imports can re-read it without asking again.
            if (sourceLocation != null) {
                conn.add(ds, pSourceLocation(), vf.createLiteral(sourceLocation), ctxMeta);
            }

            String now = OffsetDateTime.now().toString();
            conn.add(ds, DCTERMS.CREATED, vf.createLiteral(now), ctxMeta);
            conn.add(ds, pLastSync(), vf.createLiteral(now), ctxMeta);
            conn.add(ds, pGraph(), ctxGraph, ctxMeta);

            conn.commit(); // the metadata first: the import below reads it
        }

        if ((file != null && !file.isEmpty()) || sourceLocation != null) {
            return importIntoSource(req.getShortName(), new ImportRequest(null, null, null, null, file));
        }
        return null;
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
     * What to import into an external source; every null field means "as recorded for the
     * source".
     *
     * @param importer a plugin name or {@code rdf-file}
     * @param location a path on this machine (file or directory); recorded once the import succeeds
     * @param upload   the bytes of an RDF file (RDF files only); wins over any path
     */
    public record ImportRequest(String importer, Map<String, String> options, String baseIri, String location,
                                MultipartFile upload) {
    }

    /** Replaces the content of an external source's graph (re-import); see {@link #importIntoSource}. */
    public ImportResult synchronizeExternalDataSource(String shortName, ImportRequest request) {
        return importIntoSource(shortName, request);
    }

    /**
     * Replaces the content of an external source's graph with what its importer reads, in one
     * transaction: the graph is cleared, the importer streams its statements into it (no file and
     * no intermediate model in between), then {@code app:lastSync}, the location, the importer,
     * its options and base IRI and {@code app:lastImport} are recorded. Any failure rolls
     * everything back: the source keeps its previous content.
     */
    public ImportResult importIntoSource(String shortName, ImportRequest req) {
        requireProjectOpen();
        validateShortName(shortName);

        IRI ctxMeta = metaCtx();
        IRI ds = dsIri(shortName);

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ds, pSourceType(), null, false, ctxMeta)) {
                throw new NotFoundException("Source introuvable : " + shortName);
            }
            if (!"external".equals(getLiteral(conn, ds, pSourceType(), ctxMeta))) {
                throw new BadRequestException("La synchronisation est réservée aux sources externes");
            }
            String graphIri = getIri(conn, ds, pGraph(), ctxMeta);
            if (graphIri == null) {
                throw new BadRequestException("Graphe manquant pour la source : " + shortName);
            }
            IRI ctxGraph = vf.createIRI(graphIri);

            // The importer: the one chosen in the form, else the recorded one
            String storedImporter = getLiteral(conn, ds, pSourceTool(), ctxMeta);
            Resolution resolution = importers.resolve(req.importer() != null ? req.importer() : storedImporter);
            if (resolution.status() == Status.MISSING) {
                Path userDir = importers.snapshot().userDirectory();
                throw new ConflictException("No plugin for this data source: \"" + resolution.rawValue() + "\". "
                        + "Put the plugin in " + (userDir != null ? userDir : "the plugin directory")
                        + " or turn the value to raw RDF.");
            }
            Importer importer = resolution.importer();
            boolean plugin = resolution.status() == Status.PLUGIN;

            // The input: an upload (RDF files only), else a path on this machine
            MultipartFile upload = req.upload() != null && !req.upload().isEmpty() ? req.upload() : null;
            if (upload != null && plugin) {
                throw new BadRequestException("The " + importer.label() + " importer reads a path on this machine: "
                        + "give the path instead of uploading a file.");
            }
            String givenLocation = normalizeFileLocation(req.location());
            String location = givenLocation != null ? givenLocation
                    : normalizeFileLocation(getLiteral(conn, ds, pSourceLocation(), ctxMeta));
            if (upload == null) {
                if (location == null) {
                    throw new BadRequestException("No location recorded for this source. "
                            + "Set its path, or re-import by selecting the file.");
                }
                if (!Files.exists(Path.of(location))) {
                    throw new BadRequestException((plugin ? "Not found: " : "RDF file not found: ")
                            + Path.of(location).toAbsolutePath());
                }
            }

            ImportSettings settings;
            if (plugin) {
                Map<String, String> options = req.options() != null
                        ? cleanOptions(req.options(), importer)
                        : readOptions(conn, ds, ctxMeta);
                String baseIri = blankToNull(req.baseIri());
                if (baseIri == null) baseIri = getLiteral(conn, ds, pImportBaseIri(), ctxMeta);
                if (baseIri == null) baseIri = defaultBaseIri(shortName);
                settings = new ImportSettings(baseIri, options);
            } else {
                String prefix = projectService.readCurrentProject().prefix;
                settings = new ImportSettings(prefix == null ? "" : prefix, Map.of());
            }

            if (upload != null) {
                try (InputStream in = upload.getInputStream()) {
                    ImportInput input = ImportInput.ofUpload(in, upload.getOriginalFilename());
                    return importers.use(() -> runImport(conn, ds, ctxGraph, ctxMeta, importer, plugin, input,
                            settings, givenLocation));
                } catch (IOException e) {
                    throw new BadRequestException("Unreadable uploaded file " + upload.getOriginalFilename()
                            + " : " + e.getMessage());
                }
            }
            ImportInput input = ImportInput.ofPath(Path.of(location));
            return importers.use(() -> runImport(conn, ds, ctxGraph, ctxMeta, importer, plugin, input, settings,
                    givenLocation));
        }
    }

    private ImportResult runImport(RepositoryConnection conn, IRI ds, IRI ctxGraph, IRI ctxMeta, Importer importer,
                                   boolean plugin, ImportInput input, ImportSettings settings, String newLocation) {
        conn.begin();
        try {
            GraphReplacingSink sink = new GraphReplacingSink(conn, ctxGraph);
            List<String> warnings;
            try (ImportWarningCollector collector = ImportWarningCollector.start()) {
                conn.clear(ctxGraph);
                importer.emit(input, settings, sink);
                warnings = collector.warnings();
            }

            String now = OffsetDateTime.now().toString();
            replaceLiteral(conn, ds, pLastSync(), now, ctxMeta);
            if (newLocation != null) {
                replaceLiteral(conn, ds, pSourceLocation(), newLocation, ctxMeta);
            }
            replaceLiteral(conn, ds, pSourceTool(), importer.id(), ctxMeta);
            if (plugin) {
                writeOptions(conn, ds, settings.options(), ctxMeta);
                replaceLiteral(conn, ds, pImportBaseIri(), settings.baseIri(), ctxMeta);
            }
            LastImportDto last = new LastImportDto();
            last.importer = importer.id();
            last.importerVersion = importer.version();
            last.statements = sink.count();
            last.date = now;
            last.input = input.label();
            last.warnings = warnings;
            replaceLiteral(conn, ds, pLastImport(), toJson(last), ctxMeta);

            conn.commit();
            publishDataChanged();

            ImportResult result = ImportResult.success(getLiteral(conn, ds, pShortName(), ctxMeta), sink.count());
            result.importer = importer.id();
            result.warnings = warnings;
            return result;
        } catch (RuntimeException | LinkageError e) {
            if (conn.isActive()) conn.rollback();
            throw importFailure(e, importer, input);
        }
    }

    /** A failed import as the exception reported to the client (the transaction is already rolled back). */
    private RuntimeException importFailure(Throwable e, Importer importer, ImportInput input) {
        if (e instanceof BadRequestException || e instanceof ConflictException || e instanceof NotFoundException) {
            return (RuntimeException) e;
        }
        if (e instanceof LinkageError) {
            return new ServerErrorException("The " + importer.label() + " plugin is not compatible with this "
                    + "version of FieldArchive (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
        String where = e instanceof RDFParseException parse && parse.getLineNumber() > 0
                ? " (line " + parse.getLineNumber() + ")" : "";
        // A malformed input is the caller's problem, not a server fault: report it as a 400 naming it.
        String prefix = importer.origin() == ImporterOrigin.BUILT_IN
                ? "Import failed for " + input.label()
                : "Import with " + importer.label() + " failed for " + input.label();
        return new BadRequestException(prefix + where + " : " + e.getMessage());
    }

    // --- importer metadata helpers ---

    /**
     * The id to store for a requested importer: null or blank means an RDF file; an unknown name
     * is refused, unless it is the value already stored (a source whose plugin is missing).
     */
    private String requestedImporterId(String requested, String stored) {
        Resolution resolution = importers.resolve(requested);
        if (resolution.status() == Status.MISSING
                && (stored == null || !requested.trim().equalsIgnoreCase(stored.trim()))) {
            throw new BadRequestException("Unknown importer: " + requested);
        }
        return resolution.id();
    }

    /** The options worth storing: non-blank, and different from the plugin's default when known. */
    private static Map<String, String> cleanOptions(Map<String, String> options, Importer importer) {
        Map<String, String> out = new LinkedHashMap<>();
        if (options == null) return out;
        Map<String, String> defaults = new LinkedHashMap<>();
        if (importer != null) {
            for (OptionDescriptor o : importer.options()) defaults.put(o.name(), o.defaultValue());
        }
        options.forEach((name, value) -> {
            if (name == null || value == null || value.isBlank()) return;
            if (importer != null && !defaults.containsKey(name)) return;
            if (value.trim().equals(defaults.get(name))) return;
            out.put(name, value.trim());
        });
        return out;
    }

    private Map<String, String> readOptions(RepositoryConnection conn, IRI ds, IRI ctxMeta) {
        Map<String, String> options = readJson(getLiteral(conn, ds, pImporterOptions(), ctxMeta),
                new TypeReference<Map<String, String>>() {});
        return options == null ? new LinkedHashMap<>() : options;
    }

    private void writeOptions(RepositoryConnection conn, IRI ds, Map<String, String> options, IRI ctxMeta) {
        replaceLiteral(conn, ds, pImporterOptions(), options == null || options.isEmpty() ? null : toJson(options),
                ctxMeta);
    }

    /** The base IRI proposed for a plugin: {@code <project prefix>/source/<shortName>}. */
    String defaultBaseIri(String shortName) {
        String prefix = projectService.readCurrentProject().prefix;
        String p = prefix == null ? "" : prefix.trim();
        while (p.endsWith("/") || p.endsWith("#")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) {
            String project = String.valueOf(ProjectContext.getProjectName()).replaceAll("[^A-Za-z0-9._-]", "_");
            return "urn:fieldarchive:" + project + ":source:" + shortName;
        }
        return p + "/source/" + shortName;
    }

    /** Replaces the value of {@code predicate}; a null value only removes it. */
    private void replaceLiteral(RepositoryConnection conn, IRI ds, IRI predicate, String value, IRI ctxMeta) {
        conn.remove(ds, predicate, null, ctxMeta);
        if (value != null) conn.add(ds, predicate, vf.createLiteral(value), ctxMeta);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, type);
        } catch (IOException e) {
            return null;
        }
    }

    private static <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, type);
        } catch (IOException e) {
            return null;
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