package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * The triples the application maintains itself, whatever way the data was changed:
 * {@code dcterms:created} / {@code dcterms:modified} on entities of the internal graph, and
 * the internal data source's {@code dcterms:modified} in {@code CTX_META}.
 * Shared by {@link RdfEntityService} (UI edits) and {@link SparqlService} (SPARQL updates).
 */
@Component
public class InternalDataBookkeeping {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    public static final String DCTERMS_CREATED = "http://purl.org/dc/terms/created";
    public static final String DCTERMS_MODIFIED = "http://purl.org/dc/terms/modified";

    private IRI pCreated() { return vf.createIRI(DCTERMS_CREATED); }
    private IRI pModified() { return vf.createIRI(DCTERMS_MODIFIED); }

    /** Refreshes the internal data source's modification date. */
    public void touchInternalDataSource(RepositoryConnection conn) {
        IRI ctxMeta = vf.createIRI(RdfContexts.CTX_META);
        String projectName = ProjectContext.getProjectName();
        IRI ds = vf.createIRI(RdfNamespaces.APP + "/datasource/" + projectName + "_internal");

        String now = OffsetDateTime.now().toString();
        conn.remove(ds, pModified(), null, ctxMeta);
        conn.add(ds, pModified(), vf.createLiteral(now), ctxMeta);
    }

    /**
     * Applied inside the transaction of a SPARQL update, after it ran.
     *
     * @param changedSubjects subjects that had statements added or removed in the internal graph
     * @param existedBefore   the subset of them that had at least one statement in the internal
     *                        graph before the update (read on a separate connection, which does
     *                        not see the uncommitted update)
     */
    public void afterUpdate(RepositoryConnection conn, IRI internalCtx,
                            Set<Resource> changedSubjects, Set<Resource> existedBefore) {
        if (changedSubjects.isEmpty()) return;

        Literal now = vf.createLiteral(Instant.now().toString(), XSD.DATETIME);
        for (Resource s : changedSubjects) {
            if (!(s instanceof IRI subject)) continue;
            boolean existsNow = conn.hasStatement(subject, null, null, false, internalCtx);
            if (!existsNow) continue; // deleted: nothing to date

            if (!existedBefore.contains(subject)) {
                if (conn.hasStatement(subject, RDF.TYPE, null, false, internalCtx)
                        && !conn.hasStatement(subject, pCreated(), null, false, internalCtx)) {
                    conn.add(subject, pCreated(), now, internalCtx);
                }
            } else {
                conn.remove(subject, pModified(), null, internalCtx);
                conn.add(subject, pModified(), now, internalCtx);
            }
        }
        touchInternalDataSource(conn);
    }
}
