package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.dtos.ImportResult;
import fr.cnrs.lacito.fieldarchive.exceptions.ImportException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.util.RDFInserter;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Service
public class FileImportService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private final ProjectService projectService;
    private final DataSourceService dsService;
    private final BuiltinOntologyService builtinOntologyService; // ✅ pour recharger RICO après restauration

    public FileImportService(ProjectService projectService,
                             DataSourceService dsService,
                             BuiltinOntologyService builtinOntologyService
    ) {
        this.projectService = projectService;
        this.dsService = dsService;
        this.builtinOntologyService = builtinOntologyService;
    }

    public ImportResult importTurtle(InputStream ttlStream, String baseURI) {
        Repository repo = ProjectContext.getRepository();
        if (repo == null) {
            throw new ImportException("No project opened");
        }
//        IRI CTX_INTERNAL = SimpleValueFactory.getInstance()
//                .createIRI("http://fieldarchive.local/source/" + sourceName);
//        IRI CTX_INTERNAL = vf.createIRI("urn:datasource:"+this.projectService.readCurrentProject().prefix+"_internal");
        String projectName = projectService.readCurrentProject().name;
        IRI CTX_INTERNAL = dsService.getGraphIri(projectName + "_internal"); // ✅ lu depuis les métadonnées

        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            // Re-import semantics: wipe the named graph before reloading
//            conn.clear(CTX_INTERNAL);

            RDFInserter inserter = new RDFInserter(conn);
            inserter.enforceContext(CTX_INTERNAL); // force every triple into this named graph

            RDFParser parser = Rio.createParser(RDFFormat.TURTLE);
            parser.setRDFHandler(inserter);
            parser.getParserConfig()
                    .set(BasicParserSettings.VERIFY_RELATIVE_URIS, false);

            parser.parse(ttlStream, baseURI);

            conn.commit();
            return ImportResult.success(CTX_INTERNAL.stringValue(), conn.size(CTX_INTERNAL));
        } catch (RDFParseException e) {
            // line/column info is on the exception — surface it to the frontend
            throw new ImportException(e.getLineNumber(), e.getColumnNumber(), e.getMessage());
        } catch (Exception e) {
            throw new ImportException("Import failed: " + e.getMessage());
        }
    }

    /* Restaure un projet depuis un backup (.zip contenant un .trig avec
        * les graphes InternalDataSource + métadonnées projet/datasource).
        * Le nom du projet est déduit du chemin interne du zip
        * (dossier parent de project-backup.trig), pas du nom de fichier uploadé.
    */
    public ImportResult importBackup(InputStream zipStream) {
        byte[] trigContent = null;
        String projectName = null;

        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith("project-backup.trig")) {
                    // ex: "archive2025/project-backup.trig" → "archive2025"
                    String entryPath = entry.getName();
                    int slash = entryPath.lastIndexOf('/');
                    if (slash <= 0) {
                        throw new ImportException("Invalid Backup Project : Can't find project's name.");
                    }
                    projectName = entryPath.substring(0, slash);

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    zip.transferTo(buffer);
                    trigContent = buffer.toByteArray();
                    break;
                }
            }
        } catch (Exception e) {
            throw new ImportException("Impossible de lire l'archive : " + e.getMessage());
        }

        if (trigContent == null || projectName == null) {
            throw new ImportException("No file project-backup.trig found.");
        }

        // 1) Create a new empty repo (fails if already exists)
        projectService.initEmptyProjectRepository(projectName, true);

        Repository repo = ProjectContext.getRepository();

        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            try (InputStream trigStream = new ByteArrayInputStream(trigContent)) {
                RDFParser parser = Rio.createParser(RDFFormat.TRIG);
                parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false); // ✅ tolère les IRIs non strictement conformes (double '#')
                parser.getParserConfig().set(BasicParserSettings.VERIFY_RELATIVE_URIS, false);

                RDFInserter inserter = new RDFInserter(conn); // pas de enforceContext() : conserve les graphes tels qu'écrits dans le .trig
                parser.setRDFHandler(inserter);
                parser.parse(trigStream, "");
            }

            conn.commit();
        } catch (Exception e) {
            throw new ImportException("Failed to extract backup project's content: " + e.getMessage());
        }

        // 3) Recharger l'ontologie RICO — absente du backup, l'app en a besoin.
        //    ensureRicoLoaded() est idempotent : il ne réécrit rien si déjà présent.
        builtinOntologyService.ensureRicoLoaded();

        return ImportResult.success(projectName, repo.getConnection().size());
    }

}
