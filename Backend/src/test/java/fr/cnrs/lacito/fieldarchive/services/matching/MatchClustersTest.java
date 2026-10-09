package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchStatus;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.MatchClusters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatchClustersTest {

    static MatchLink link(String id, String dsA, String iriA, String dsB, String iriB, MatchStatus status) {
        MatchLink l = new MatchLink();
        l.id = id;
        l.a = new EntityNodeDto(dsA, iriA);
        l.b = new EntityNodeDto(dsB, iriB);
        l.status = status;
        return l;
    }

    @Test
    void confirmedLinksAndIdenticalIrisMakeClusters() {
        List<MatchLink> links = List.of(
                link("1", "A", "a", "B", "b", MatchStatus.ACCEPTED),
                link("2", "B", "b", "C", "c", MatchStatus.MANUAL),
                link("3", "A", "x", "B", "y", MatchStatus.PROPOSED));
        MatchClusters c = MatchClusters.build(links, Map.of("b", Set.of("B", "D")));
        EntityNode a = new EntityNode("A", "a"), cc = new EntityNode("C", "c");
        assertThat(c.sameCluster(a, cc)).isTrue();
        assertThat(c.clusterOf(a)).hasSize(4);                     // a, b@B, c, b@D
        assertThat(c.memberIn(a, "D")).contains(new EntityNode("D", "b"));
        assertThat(c.sameCluster(new EntityNode("A", "x"), new EntityNode("B", "y"))).isFalse();
        assertThat(c.path(a, cc)).extracting(MatchClusters.Step::edgeId).containsExactly("1", "2");
        assertThat(c.sameEntity("a", "c")).isTrue();
    }

    @Test
    void invariants() {
        List<MatchLink> links = List.of(
                link("1", "A", "a", "B", "b", MatchStatus.ACCEPTED),
                link("2", "C", "c", "B", "b2", MatchStatus.ACCEPTED),
                link("r", "A", "a", "C", "c", MatchStatus.REJECTED));
        MatchClusters c = MatchClusters.build(links, Map.of());
        // I1: a's cluster has b@B, c's cluster has b2@B
        assertThat(c.mergeViolations(new EntityNode("A", "a"), new EntityNode("C", "c"), links, "r"))
                .anyMatch(v -> v.contains("same data source"));
        // I2: joining a and c through a new node is refused because of the rejection
        assertThat(c.mergeViolations(new EntityNode("A", "a"), new EntityNode("D", "d"), links, null)).isEmpty();
        List<MatchLink> withD = List.of(links.get(0), links.get(1), links.get(2), link("3", "A", "a", "D", "d", MatchStatus.ACCEPTED));
        MatchClusters c2 = MatchClusters.build(withD, Map.of());
        assertThat(c2.mergeViolations(new EntityNode("D", "d"), new EntityNode("C", "c"), withD, null))
                .anyMatch(v -> v.contains("rejected"));
    }
}
