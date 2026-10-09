package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The IRIs that are subjects in at least two data graphs: the same entity, by construction.
 * Cached until the project data changes.
 */
@Component
public class IdenticalIriFinder {

    private final DataSourceCatalog catalog;
    private Map<String, Set<String>> cache;
    private String cachedProject;

    public IdenticalIriFinder(DataSourceCatalog catalog) {
        this.catalog = catalog;
    }

    @EventListener
    public synchronized void onDataChanged(ProjectDataChangedEvent event) {
        cache = null;
    }

    /** IRI → short names of the data sources in which it is a subject (two or more). */
    public synchronized Map<String, Set<String>> identicalIris() {
        String project = ProjectContext.getProjectName();
        if (cache != null && Objects.equals(project, cachedProject)) return cache;
        Map<String, String> graphToSource = new HashMap<>();
        StringBuilder values = new StringBuilder();
        for (DataSourceCatalog.DataSourceInfo ds : catalog.list()) {
            graphToSource.put(ds.graph().stringValue(), ds.shortName());
            values.append('<').append(ds.graph().stringValue()).append("> ");
        }
        Map<String, Set<String>> out = new HashMap<>();
        if (graphToSource.size() >= 2) {
            String q = "SELECT DISTINCT ?s ?g WHERE { VALUES ?g { " + values + "} GRAPH ?g { ?s ?p ?o } FILTER(isIRI(?s)) }";
            Map<String, Set<String>> all = new HashMap<>();
            try (RepositoryConnection conn = ProjectContext.getRepository().getConnection();
                 TupleQueryResult r = conn.prepareTupleQuery(q).evaluate()) {
                for (BindingSet bs : r) {
                    if (bs.getValue("s") instanceof IRI s) {
                        all.computeIfAbsent(s.stringValue(), k -> new TreeSet<>())
                                .add(graphToSource.get(bs.getValue("g").stringValue()));
                    }
                }
            }
            all.forEach((iri, sources) -> {
                if (sources.size() > 1) out.put(iri, sources);
            });
        }
        cache = out;
        cachedProject = project;
        return out;
    }
}
