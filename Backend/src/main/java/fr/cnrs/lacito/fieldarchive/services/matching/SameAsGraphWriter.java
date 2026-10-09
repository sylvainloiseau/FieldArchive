package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.MatchClusters;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rewrites the {@code owl:sameAs} graph ({@link RdfContexts#CTX_MATCHING}) from the clusters:
 * for every two distinct IRIs of a cluster, both {@code x owl:sameAs y} and
 * {@code y owl:sameAs x}, so that a plain triple pattern finds every equivalent without
 * reasoning. Derived data: it does not publish a data change event.
 */
@Component
public class SameAsGraphWriter {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    public static IRI graph() {
        return vf.createIRI(RdfContexts.CTX_MATCHING);
    }

    /** @return the number of triples written */
    public int rewrite(MatchClusters clusters) {
        IRI ctx = graph();
        int count = 0;
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            conn.begin();
            conn.clear(ctx);
            for (Set<EntityNode> cluster : clusters.clusters(2)) {
                Set<String> iris = new TreeSet<>();
                for (EntityNode n : cluster) iris.add(n.iri());
                List<String> list = new ArrayList<>(iris);
                for (String x : list) {
                    for (String y : list) {
                        if (x.equals(y)) continue;
                        conn.add(vf.createIRI(x), OWL.SAMEAS, vf.createIRI(y), ctx);
                        count++;
                    }
                }
            }
            conn.commit();
        }
        return count;
    }
}
