package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.exceptions.ImportException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FileExportService {
    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private final ProjectService projectService;
    private final DataSourceService dsService;

    public FileExportService(ProjectService projectService,DataSourceService dsService) {
        this.projectService = projectService;
        this.dsService = dsService;
    }

    /**
     * Exports only the InternalDataSource named graph (the data the user
     * actually created/imported in the app) as plain Turtle triples.
     * The named graph itself is NOT part of the output — Turtle has no
     * notion of context, so the statements come out as bare triples,
     * ready to be fed into any other RDF-consuming application.
     */
    public void exportInternalDataSource(OutputStream out) {
        Repository repo = ProjectContext.getRepository();
        if (repo == null) {
            throw new ImportException("No project opened");
        }

        String projectName = projectService.readCurrentProject().name;
        IRI ctxInternal = dsService.getGraphIri(projectName + "_internal"); // ✅ idem

        try (RepositoryConnection conn = repo.getConnection()) {
            RDFWriter writer = Rio.createWriter(RDFFormat.TURTLE, out);

            conn.begin();
            writer.startRDF();

            try (RepositoryResult<Statement> statements =
                         conn.getStatements(null, null, null, false, ctxInternal)) {
                for (Statement st : statements) {
                    // Re-emit as a context-free statement so the writer never
                    // even sees the graph — belt and braces alongside Turtle's
                    // own lack of context support.
                    writer.handleStatement(
                            vf.createStatement(st.getSubject(), st.getPredicate(), st.getObject()));
                }
            }

            writer.endRDF();
            conn.commit();
        } catch (Exception e) {
            throw new ImportException("Export failed: " + e.getMessage());
        }
    }

    public void exportBackup(OutputStream zipOut) {
        Repository repo = ProjectContext.getRepository();
        if (repo == null) {
            throw new ImportException("No project opened");
        }

        String projectName = projectService.readCurrentProject().name;
        IRI ctxInternal = dsService.getGraphIri(projectName + "_internal"); // ✅
        IRI ctxMeta = vf.createIRI(RdfContexts.CTX_META);
        IRI ctxProjectMeta = projectService.getMetadataContext(projectName, vf); // ✅ ajouté

        try (RepositoryConnection conn = repo.getConnection()) {
            ZipOutputStream zip = new ZipOutputStream(zipOut);
            zip.putNextEntry(new ZipEntry(projectName+"/project-backup.trig"));

            RDFWriter writer = Rio.createWriter(RDFFormat.TRIG, zip);

            conn.begin();
            writer.startRDF();

            try (RepositoryResult<Statement> statements =
                         conn.getStatements(null, null, null, false, ctxInternal, ctxMeta,ctxProjectMeta)) {
                for (Statement st : statements) {
                    writer.handleStatement(st); // keep context — this is a quad export
                }
            }

            writer.endRDF();
            conn.commit();

            zip.closeEntry();
            zip.finish();
        } catch (Exception e) {
            throw new ImportException("Backup export failed: " + e.getMessage());
        }
    }
}
