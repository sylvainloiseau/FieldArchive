package fr.cnrs.lacito.fieldarchive.services.matching.cluster;

import fr.cnrs.lacito.fieldarchive.dtos.matching.ConflictDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchStatus;

import java.util.*;

/**
 * The equivalence classes ("clusters") of entity nodes. Two nodes are in the same cluster when
 * a path of confirmed links (ACCEPTED, MANUAL) and identical IRIs (the same IRI in two data
 * sources) joins them. Built with union-find; a node that is in no link is its own singleton.
 */
public final class MatchClusters {

    public static final String IDENTICAL_PREFIX = "identical:";

    /** One edge of a path: a link id or {@code identical:<iri>}, between two nodes. */
    public record Step(String edgeId, EntityNode from, EntityNode to) {
        public boolean isIdentical() {
            return edgeId.startsWith(IDENTICAL_PREFIX);
        }
    }

    private record Edge(String id, EntityNode other) {}

    private final Map<EntityNode, EntityNode> parent = new HashMap<>();
    private final Map<EntityNode, List<Edge>> adjacency = new HashMap<>();
    private final Map<String, Set<EntityNode>> nodesByIri = new HashMap<>();
    private Map<EntityNode, Set<EntityNode>> members;

    private MatchClusters() {}

    /**
     * @param links         all stored links; only the confirmed ones are edges
     * @param identicalIris IRI → the data sources in which it has statements (at least two)
     */
    public static MatchClusters build(Collection<? extends MatchLink> links, Map<String, Set<String>> identicalIris) {
        MatchClusters c = new MatchClusters();
        for (MatchLink l : links) {
            if (l.status != null && l.status.isConfirmed()) {
                c.addEdge(l.id, EntityNode.of(l.a), EntityNode.of(l.b));
            }
        }
        identicalIris.forEach((iri, sources) -> {
            List<String> sorted = new ArrayList<>(new TreeSet<>(sources));
            for (int i = 1; i < sorted.size(); i++) {
                c.addEdge(IDENTICAL_PREFIX + iri, new EntityNode(sorted.get(i - 1), iri), new EntityNode(sorted.get(i), iri));
            }
        });
        return c;
    }

    private void addEdge(String id, EntityNode x, EntityNode y) {
        adjacency.computeIfAbsent(x, k -> new ArrayList<>()).add(new Edge(id, y));
        adjacency.computeIfAbsent(y, k -> new ArrayList<>()).add(new Edge(id, x));
        nodesByIri.computeIfAbsent(x.iri(), k -> new HashSet<>()).add(x);
        nodesByIri.computeIfAbsent(y.iri(), k -> new HashSet<>()).add(y);
        union(x, y);
        members = null;
    }

    private EntityNode find(EntityNode n) {
        EntityNode p = parent.get(n);
        if (p == null) return n;
        EntityNode root = find(p);
        parent.put(n, root);
        return root;
    }

    private void union(EntityNode x, EntityNode y) {
        EntityNode rx = find(x), ry = find(y);
        if (!rx.equals(ry)) parent.put(rx, ry);
    }

    private Map<EntityNode, Set<EntityNode>> members() {
        if (members == null) {
            members = new HashMap<>();
            for (EntityNode n : adjacency.keySet()) {
                members.computeIfAbsent(find(n), k -> new LinkedHashSet<>()).add(n);
            }
        }
        return members;
    }

    // =========================
    //  Queries
    // =========================

    /** The nodes of {@code n}'s cluster, {@code n} included. */
    public Set<EntityNode> clusterOf(EntityNode n) {
        Set<EntityNode> m = members().get(find(n));
        return m != null ? Collections.unmodifiableSet(m) : Set.of(n);
    }

    public boolean sameCluster(EntityNode x, EntityNode y) {
        return x.equals(y) || find(x).equals(find(y));
    }

    /** The node of {@code n}'s cluster belonging to {@code dataSource}, if any. */
    public Optional<EntityNode> memberIn(EntityNode n, String dataSource) {
        for (EntityNode m : clusterOf(n)) {
            if (m.dataSource().equals(dataSource)) return Optional.of(m);
        }
        return Optional.empty();
    }

    /** Whether two IRIs denote the same entity: equal, or in the same cluster through any of their nodes. */
    public boolean sameEntity(String iri1, String iri2) {
        if (iri1.equals(iri2)) return true;
        Set<EntityNode> n1 = nodesByIri.get(iri1);
        Set<EntityNode> n2 = nodesByIri.get(iri2);
        if (n1 == null || n2 == null) return false;
        EntityNode root = find(n1.iterator().next());
        for (EntityNode n : n2) if (find(n).equals(root)) return true;
        return false;
    }

    /** Clusters with at least {@code minSize} nodes. */
    public List<Set<EntityNode>> clusters(int minSize) {
        List<Set<EntityNode>> out = new ArrayList<>();
        for (Set<EntityNode> m : members().values()) if (m.size() >= minSize) out.add(m);
        return out;
    }

    /** Shortest path between two nodes of the same cluster (empty if they are not connected). */
    public List<Step> path(EntityNode from, EntityNode to) {
        if (from.equals(to) || !sameCluster(from, to)) return List.of();
        Map<EntityNode, Step> reachedBy = new HashMap<>();
        Deque<EntityNode> queue = new ArrayDeque<>(List.of(from));
        Set<EntityNode> seen = new HashSet<>(List.of(from));
        while (!queue.isEmpty()) {
            EntityNode n = queue.poll();
            if (n.equals(to)) break;
            List<Edge> edges = new ArrayList<>(adjacency.getOrDefault(n, List.of()));
            edges.sort(Comparator.comparing(Edge::id));     // deterministic paths
            for (Edge e : edges) {
                if (seen.add(e.other())) {
                    reachedBy.put(e.other(), new Step(e.id(), n, e.other()));
                    queue.add(e.other());
                }
            }
        }
        LinkedList<Step> path = new LinkedList<>();
        for (EntityNode n = to; reachedBy.containsKey(n); n = reachedBy.get(n).from()) {
            path.addFirst(reachedBy.get(n));
        }
        return path;
    }

    // =========================
    //  Invariants
    // =========================

    /**
     * Why confirming {@code a ≡ b} is not allowed, if it is not: it would put two different IRIs
     * of the same data source in one cluster (I1), or join two entities the user rejected (I2).
     *
     * @param rejected       the REJECTED links to respect
     * @param ignoredLinkId  a rejected link not to count (the link being accepted itself), or null
     */
    public List<String> mergeViolations(EntityNode a, EntityNode b, Collection<? extends MatchLink> rejected, String ignoredLinkId) {
        List<String> out = new ArrayList<>();
        if (a.dataSource().equals(b.dataSource())) {
            out.add("Both entities belong to the data source \"" + a.dataSource() + "\".");
            return out;
        }
        if (sameCluster(a, b)) return out;
        Set<EntityNode> ca = clusterOf(a), cb = clusterOf(b);
        for (EntityNode x : ca) {
            for (EntityNode y : cb) {
                if (x.dataSource().equals(y.dataSource()) && !x.iri().equals(y.iri())) {
                    out.add(x + " and " + y + " would both be the same entity, but they belong to the same data source \""
                            + x.dataSource() + "\".");
                }
            }
        }
        for (MatchLink r : rejected) {
            if (r.status != MatchStatus.REJECTED || Objects.equals(r.id, ignoredLinkId)) continue;
            EntityNode ra = EntityNode.of(r.a), rb = EntityNode.of(r.b);
            if (ca.contains(ra) && cb.contains(rb) || ca.contains(rb) && cb.contains(ra)) {
                out.add(ra + " and " + rb + " were rejected as different entities (link " + r.id + ").");
            }
        }
        return out;
    }

    /** Inconsistencies present in the current links (possible after a sync or an identical IRI). */
    public List<ConflictDto> conflicts(Collection<? extends MatchLink> links) {
        List<ConflictDto> out = new ArrayList<>();
        for (Set<EntityNode> cluster : clusters(2)) {
            Map<String, Set<EntityNode>> bySource = new TreeMap<>();
            for (EntityNode n : cluster) bySource.computeIfAbsent(n.dataSource(), k -> new LinkedHashSet<>()).add(n);
            bySource.forEach((source, nodes) -> {
                if (nodes.size() > 1) {
                    ConflictDto c = new ConflictDto();
                    c.kind = "SAME_SOURCE_IN_CLUSTER";
                    nodes.forEach(n -> c.nodes.add(n.toDto()));
                    for (MatchLink l : links) {
                        if (l.status != null && l.status.isConfirmed()
                                && (cluster.contains(EntityNode.of(l.a)) || cluster.contains(EntityNode.of(l.b)))) {
                            c.linkIds.add(l.id);
                        }
                    }
                    c.message = nodes.size() + " entities of the data source \"" + source
                            + "\" are linked to the same entity; undo one of the links.";
                    out.add(c);
                }
            });
        }
        for (MatchLink l : links) {
            if (l.status == MatchStatus.REJECTED && sameCluster(EntityNode.of(l.a), EntityNode.of(l.b))) {
                ConflictDto c = new ConflictDto();
                c.kind = "REJECTED_INSIDE_CLUSTER";
                c.nodes.add(l.a);
                c.nodes.add(l.b);
                c.linkIds.add(l.id);
                c.message = "These two entities were rejected as different, but other links make them the same entity.";
                out.add(c);
            }
        }
        return out;
    }
}
