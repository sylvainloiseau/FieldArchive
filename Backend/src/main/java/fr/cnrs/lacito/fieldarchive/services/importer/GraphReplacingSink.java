package fr.cnrs.lacito.fieldarchive.services.importer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.util.RDFInserter;
import org.eclipse.rdf4j.rio.helpers.RDFHandlerWrapper;

/**
 * The handler every importer writes into: statements go straight into {@code graph} on
 * {@code conn} (whatever context the importer gives them), inside the transaction the caller
 * opened, and are counted. Namespaces are added to the store when their prefix is not defined
 * yet ({@link RDFInserter}'s behaviour).
 */
public final class GraphReplacingSink extends RDFHandlerWrapper {

    private long count;

    public GraphReplacingSink(RepositoryConnection conn, IRI graph) {
        super(inserter(conn, graph));
    }

    private static RDFInserter inserter(RepositoryConnection conn, IRI graph) {
        RDFInserter inserter = new RDFInserter(conn);
        inserter.enforceContext(graph);
        return inserter;
    }

    @Override
    public void handleStatement(Statement st) {
        super.handleStatement(st);
        count++;
    }

    public long count() {
        return count;
    }
}
