package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.HashMap;
import java.util.Map;

/**
 * Whether a node still exists: its data source exists and its IRI has statements in that
 * source's graph. One instance per request (results are cached).
 */
public final class OrphanDetector {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private final RepositoryConnection conn;
    private final Map<String, IRI> graphBySource;
    private final Map<EntityNode, Boolean> cache = new HashMap<>();

    public OrphanDetector(RepositoryConnection conn, Map<String, IRI> graphBySource) {
        this.conn = conn;
        this.graphBySource = graphBySource;
    }

    public boolean isOrphan(EntityNode node) {
        return cache.computeIfAbsent(node, n -> {
            IRI graph = graphBySource.get(n.dataSource());
            if (graph == null) return true;
            return !conn.hasStatement(vf.createIRI(n.iri()), null, null, false, graph);
        });
    }
}
