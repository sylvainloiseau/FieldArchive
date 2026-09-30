package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.parser.ParsedOperation;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.sail.NotifyingSailConnection;
import org.eclipse.rdf4j.sail.SailConnectionListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SparqlService {

    /** Execution limit, in seconds, of a SELECT sent from the SPARQL page. */
    private static final int DEFAULT_SELECT_SECONDS = 60;

    private final DataSourceService dsService;
    private final InternalDataBookkeeping bookkeeping;
    private final ApplicationEventPublisher events;

    public SparqlService(@Lazy DataSourceService dsService, InternalDataBookkeeping bookkeeping,
                         ApplicationEventPublisher events) {
        this.dsService = dsService;
        this.bookkeeping = bookkeeping;
        this.events = events;
    }

    /** Parses a SPARQL query or update; throws a readable 400 on a syntax error. */
    public static ParsedOperation parse(String sparql) {
        if (sparql == null || sparql.isBlank()) {
            throw new BadRequestException("The query is empty.");
        }
        try {
            return QueryParserUtil.parseOperation(QueryLanguage.SPARQL, sparql, null);
        } catch (MalformedQueryException e) {
            throw new BadRequestException("Invalid SPARQL: " + e.getMessage());
        }
    }

    public void update(String sparql) {

        requireProjectOpen();

        if (!(parse(sparql) instanceof ParsedUpdate)) {
            throw new BadRequestException("This endpoint only accepts SPARQL UPDATE requests (INSERT, DELETE…).");
        }

        Repository repo = ProjectContext.getRepository();
        IRI internalCtx = dsService.getGraphIri(ProjectContext.getProjectName() + "_internal");

        try (RepositoryConnection conn = repo.getConnection()) {

            // Collect the subjects the update touches in the internal graph, for the bookkeeping triples.
            Set<Resource> changedSubjects = new LinkedHashSet<>();
            SailConnectionListener listener = new SailConnectionListener() {
                @Override public void statementAdded(Statement st) { collect(st); }
                @Override public void statementRemoved(Statement st) { collect(st); }
                private void collect(Statement st) {
                    if (internalCtx.equals(st.getContext())) changedSubjects.add(st.getSubject());
                }
            };
            NotifyingSailConnection notifying =
                    conn instanceof SailRepositoryConnection src && src.getSailConnection() instanceof NotifyingSailConnection nsc
                            ? nsc : null;
            if (notifying != null) notifying.addConnectionListener(listener);

            conn.begin();
            try {
                Update updateQuery = conn.prepareUpdate(QueryLanguage.SPARQL, sparql);
                updateQuery.execute();

                // What existed before is read on a separate connection, which doesn't see this uncommitted update.
                // Snapshot first: the bookkeeping writes below are themselves reported to the listener.
                Set<Resource> touched = new LinkedHashSet<>(changedSubjects);
                Set<Resource> existedBefore = new HashSet<>();
                try (RepositoryConnection before = repo.getConnection()) {
                    for (Resource s : touched) {
                        if (before.hasStatement(s, null, null, false, internalCtx)) existedBefore.add(s);
                    }
                }
                bookkeeping.afterUpdate(conn, internalCtx, touched, existedBefore);
                conn.commit();
            } catch (RuntimeException e) {
                if (conn.isActive()) conn.rollback();
                throw new BadRequestException("Error while executing the UPDATE: " + e.getMessage());
            } finally {
                if (notifying != null) notifying.removeConnectionListener(listener);
            }
        }
        events.publishEvent(new ProjectDataChangedEvent(ProjectContext.getProjectName()));
    }

    private void requireProjectOpen() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("Aucun projet ouvert.");
    }

    public List<Map<String, String>> select(String sparql) {
        return select(sparql, DEFAULT_SELECT_SECONDS);
    }

    public List<Map<String, String>> select(String sparql, int maxExecutionSeconds) {
        requireProjectOpen();

        if (!(parse(sparql) instanceof ParsedTupleQuery)) {
            throw new BadRequestException("This endpoint only accepts SELECT queries.");
        }

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql);
            query.setMaxExecutionTime(maxExecutionSeconds);
            try (TupleQueryResult res = query.evaluate()) {
                List<String> vars = res.getBindingNames();
                List<Map<String, String>> out = new ArrayList<>();
                while (res.hasNext()) {
                    var b = res.next();
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String v : vars) {
                        row.put(v, b.hasBinding(v) ? b.getValue(v).stringValue() : null);
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BadRequestException("Error while executing the SELECT: " + e.getMessage());
        }
    }
}
