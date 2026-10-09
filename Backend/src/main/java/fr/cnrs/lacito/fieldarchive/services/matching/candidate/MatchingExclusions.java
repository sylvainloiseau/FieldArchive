package fr.cnrs.lacito.fieldarchive.services.matching.candidate;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchStatus;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.DataSourcePair;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.MatchClusters;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.EntityProfile;

import java.util.*;

/**
 * The pairs a run must not propose for a pair of data sources (A, B):
 * <ol>
 *   <li>an entity whose cluster already has a node in the other source (matched, by an accepted,
 *       manual or inferred link, or by an identical IRI);</li>
 *   <li>two identical IRIs;</li>
 *   <li>a rejected pair;</li>
 *   <li>a pair whose confirmation would break an invariant (two IRIs of one source in a cluster,
 *       or two rejected entities in a cluster).</li>
 * </ol>
 */
public final class MatchingExclusions {

    public enum Reason { ALREADY_MATCHED, IDENTICAL_IRI, REJECTED, INVARIANT }

    private final DataSourcePair pair;
    private final MatchClusters clusters;
    private final Set<String> rejectedPairs = new HashSet<>();
    private final List<MatchLink> rejectedLinks = new ArrayList<>();

    private MatchingExclusions(DataSourcePair pair, MatchClusters clusters) {
        this.pair = pair;
        this.clusters = clusters;
    }

    public static MatchingExclusions forPair(DataSourcePair pair, Collection<? extends MatchLink> links, MatchClusters clusters) {
        MatchingExclusions e = new MatchingExclusions(pair, clusters);
        for (MatchLink l : links) {
            if (l.status != MatchStatus.REJECTED) continue;
            e.rejectedLinks.add(l);
            if (pair.first().equals(l.a.dataSource) && pair.second().equals(l.b.dataSource)) {
                e.rejectedPairs.add(l.a.iri + "\u0000" + l.b.iri);
            }
        }
        return e;
    }

    /** Rule 1: the entity already has a counterpart in {@code otherSource}. */
    public boolean isMatchedIn(EntityProfile p, String otherSource) {
        return clusters.memberIn(p.node(), otherSource).isPresent();
    }

    /** Rules 2–4 for a ({@code a} from the first source, {@code b} from the second). */
    public Optional<Reason> reasonFor(EntityProfile a, EntityProfile b) {
        if (a.iri().equals(b.iri())) return Optional.of(Reason.IDENTICAL_IRI);
        if (rejectedPairs.contains(a.iri() + "\u0000" + b.iri())) return Optional.of(Reason.REJECTED);
        EntityNode na = a.node(), nb = b.node();
        if (clusters.clusterOf(na).size() > 1 || clusters.clusterOf(nb).size() > 1) {
            if (!clusters.mergeViolations(na, nb, rejectedLinks, null).isEmpty()) return Optional.of(Reason.INVARIANT);
        }
        return Optional.empty();
    }

    public DataSourcePair pair() {
        return pair;
    }
}
