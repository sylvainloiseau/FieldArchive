package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
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

@Service
public class FileExportService {
    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private final ProjectService projectService;

    public FileExportService(ProjectService projectService) {
        this.projectService = projectService;
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

        IRI ctxInternal = vf.createIRI(
                "urn:datasource:" + projectService.readCurrentProject().prefix + "_internal");

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
}
