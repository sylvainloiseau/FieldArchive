package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.LinkOrigin;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchStatus;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.MatchClusters;

import java.time.Instant;
import java.util.*;

/**
 * Maintains the INFERRED links so that, inside every cluster, each two nodes of different data
 * sources are joined by exactly one link record: ACCEPTED, MANUAL, or INFERRED.
 *
 * <ul>
 *   <li>a missing pair gets a new INFERRED link;</li>
 *   <li>a PROPOSED link becomes INFERRED (keeping its score and evidence);</li>
 *   <li>a REJECTED link is left alone (it is a conflict, reported elsewhere);</li>
 *   <li>an INFERRED link whose nodes are no longer in one cluster is deleted.</li>
 * </ul>
 * {@code inferredFrom} lists the edges of the shortest path joining the two nodes.
 */
public final class MatchReconciler {

    public record Result(List<MatchLink> added, List<MatchLink> removed, boolean changed) {}

    private MatchReconciler() {}

    public static Result reconcile(MatchingFile file, MatchClusters clusters) {
        List<MatchLink> added = new ArrayList<>();
        List<MatchLink> removed = new ArrayList<>();
        boolean changed = false;

        Map<String, MatchLink> byNodes = new HashMap<>();
        Map<EntityNode, EntityNodeDto> snapshots = new HashMap<>();
        for (MatchLink l : file.links) {
            byNodes.put(MatchStore.nodePairKey(l), l);
            snapshots.put(EntityNode.of(l.a), l.a);
            snapshots.put(EntityNode.of(l.b), l.b);
        }

        // 1) INFERRED links that are no longer supported
        Iterator<MatchLink> it = file.links.iterator();
        while (it.hasNext()) {
            MatchLink l = it.next();
            if (l.status == MatchStatus.INFERRED && !clusters.sameCluster(EntityNode.of(l.a), EntityNode.of(l.b))) {
                it.remove();
                byNodes.remove(MatchStore.nodePairKey(l));
                removed.add(l);
                changed = true;
            }
        }

        // 2) every pair of nodes of a cluster must be linked
        String now = Instant.now().toString();
        for (Set<EntityNode> cluster : clusters.clusters(2)) {
            List<EntityNode> nodes = new ArrayList<>(cluster);
            nodes.sort(Comparator.comparing(EntityNode::dataSource).thenComparing(EntityNode::iri));
            for (int i = 0; i < nodes.size(); i++) {
                for (int j = i + 1; j < nodes.size(); j++) {
                    EntityNode x = nodes.get(i), y = nodes.get(j);
                    if (x.dataSource().equals(y.dataSource()) || x.iri().equals(y.iri())) continue;
                    MatchLink existing = byNodes.get(MatchStore.nodePairKey(x, y));
                    if (existing != null && (existing.status.isConfirmed() || existing.status == MatchStatus.REJECTED)) continue;
                    List<String> steps = new ArrayList<>();
                    for (MatchClusters.Step s : clusters.path(x, y)) steps.add(s.edgeId());
                    if (existing == null) {
                        MatchLink l = new MatchLink();
                        l.id = UUID.randomUUID().toString();
                        l.a = copy(snapshots.getOrDefault(x, x.toDto()));
                        l.b = copy(snapshots.getOrDefault(y, y.toDto()));
                        MatchStore.canonicalize(l);
                        l.status = MatchStatus.INFERRED;
                        l.origin = LinkOrigin.INFERENCE;
                        l.inferredFrom = steps;
                        l.decidedAt = now;
                        file.links.add(l);
                        byNodes.put(MatchStore.nodePairKey(l), l);
                        added.add(l);
                        changed = true;
                    } else if (existing.status == MatchStatus.PROPOSED) {
                        existing.status = MatchStatus.INFERRED;
                        existing.origin = LinkOrigin.INFERENCE;
                        existing.inferredFrom = steps;
                        existing.decidedAt = now;
                        added.add(existing);
                        changed = true;
                    } else if (!steps.equals(existing.inferredFrom)) {
                        existing.inferredFrom = steps;
                        changed = true;
                    }
                }
            }
        }
        return new Result(added, removed, changed);
    }

    private static EntityNodeDto copy(EntityNodeDto n) {
        EntityNodeDto c = new EntityNodeDto(n.dataSource, n.iri);
        c.label = n.label;
        c.types = new ArrayList<>(n.types != null ? n.types : List.of());
        return c;
    }
}
