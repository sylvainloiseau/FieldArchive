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

@Service
public class FileImportService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private final ProjectService projectService;

    public FileImportService(ProjectService projectService) {
        this.projectService = projectService;
    }

    public ImportResult importTurtle(InputStream ttlStream, String baseURI) {
        Repository repo = ProjectContext.getRepository();
        if (repo == null) {
            throw new ImportException("No project opened");
        }
//        IRI CTX_INTERNAL = SimpleValueFactory.getInstance()
//                .createIRI("http://fieldarchive.local/source/" + sourceName);
        IRI CTX_INTERNAL = vf.createIRI("urn:datasource:"+this.projectService.readCurrentProject().prefix+"_internal");
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
}
