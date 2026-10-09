package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import fr.cnrs.lacito.fieldarchive.core.ProjectOpenedEvent;
import fr.cnrs.lacito.fieldarchive.dtos.matching.*;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.ConflictException;
import fr.cnrs.lacito.fieldarchive.exception.InvalidConfigurationException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import fr.cnrs.lacito.fieldarchive.services.OntologyService;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.DataSourcePair;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.MatchClusters;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConcept;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfiguration;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationLoader;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationService;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationWriter;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.*;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.PairScore;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Data matching: runs of the algorithm on pairs of data sources, review of the proposals
 * (accept / reject / undo), manual links, inferred links, and the {@code owl:sameAs} graph.
 * Every change to {@code data_matching.json} happens under one lock.
 */
@Service
public class MatchingService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private final MatchStore store;
    private final MatchingConfigurationService configurationService;
    private final DataSourceCatalog catalog;
    private final IdenticalIriFinder identicalIriFinder;
    private final SameAsGraphWriter sameAsGraphWriter;
    private final OntologyService ontologyService;

    private final ReentrantLock lock = new ReentrantLock();
    /** Profiles per data source, valid for one configuration and until the data changes. */
    private final Map<String, EntityProfiles> profileCache = new HashMap<>();
    private String profileCacheKey;

    public MatchingService(MatchStore store, MatchingConfigurationService configurationService, DataSourceCatalog catalog,
                           IdenticalIriFinder identicalIriFinder, SameAsGraphWriter sameAsGraphWriter,
                           OntologyService ontologyService) {
        this.store = store;
        this.configurationService = configurationService;
        this.catalog = catalog;
        this.identicalIriFinder = identicalIriFinder;
        this.sameAsGraphWriter = sameAsGraphWriter;
        this.ontologyService = ontologyService;
    }

    // =========================
    //  Events
    // =========================

    @EventListener
    public void onDataChanged(ProjectDataChangedEvent event) {
        lock.lock();
        try {
            profileCache.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Brings the inferred links and the owl:sameAs graph in line with the JSON file. Never throws. */
    @EventListener
    public void onProjectOpened(ProjectOpenedEvent event) {
        lock.lock();
        try {
            profileCache.clear();
            String project = requireProject();
            MatchingFile file = store.read(project);
            MatchClusters clusters = clusters(file);
            MatchReconciler.Result r = MatchReconciler.reconcile(file, clusters);
            if (r.changed()) store.write(project, file);
            sameAsGraphWriter.rewrite(clusters);
        } catch (RuntimeException e) {
            System.err.println("WARNING: could not synchronise the data matching links: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // =========================
    //  Overview
    // =========================

    public MatchingOverviewDto getOverview() {
        String project = requireProject();
        MatchingOverviewDto out = new MatchingOverviewDto();
        List<DataSourceCatalog.DataSourceInfo> sources = catalog.list();
        Map<String, DataSourceCatalog.DataSourceInfo> byName = new LinkedHashMap<>();
        sources.forEach(s -> byName.put(s.shortName(), s));

        String hash = null;
        try {
            out.configurationOrigin = configurationService.origin();
            hash = configurationService.effectiveConfiguration().hash();
            out.configurationHash = hash;
        } catch (InvalidConfigurationException | BadRequestException e) {
            out.configurationError = e.getMessage();
        }

        MatchingFile file = store.read(project);
        out.fileWarning = store.lastWarning();
        MatchClusters clusters = clusters(file);
        List<ConflictDto> conflicts = clusters.conflicts(file.links);
        out.conflicts = conflicts;

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            for (DataSourceCatalog.DataSourceInfo s : sources) {
                DataSourceSummaryDto d = new DataSourceSummaryDto();
                d.shortName = s.shortName();
                d.longName = s.longName();
                d.type = s.type();
                d.entityCount = countTypedEntities(conn, s.graph());
                out.dataSources.add(d);
            }

            OrphanDetector orphans = new OrphanDetector(conn, graphs(sources));
            LinkFlags flags = new LinkFlags(clusters, orphans, conflicts, file.links, null);
            Map<String, DataSourcePair> pairs = new TreeMap<>();
            List<String> names = new ArrayList<>(byName.keySet());
            for (int i = 0; i < names.size(); i++) {
                for (int j = i + 1; j < names.size(); j++) {
                    DataSourcePair p = DataSourcePair.of(names.get(i), names.get(j));
                    pairs.put(p.key(), p);
                }
            }
            for (MatchLink l : file.links) {                     // pairs of deleted sources still holding links
                DataSourcePair p = MatchStore.pairOf(l);
                pairs.putIfAbsent(p.key(), p);
            }
            Instant revoked = parseInstant(file.confirmedLinksRevokedAt);
            for (DataSourcePair p : pairs.values()) {
                PairOverviewDto po = new PairOverviewDto();
                po.key = p.key();
                po.dataSourceA = p.first();
                po.dataSourceB = p.second();
                for (MatchStatus st : MatchStatus.values()) po.counts.put(st.name(), 0);
                po.counts.put("toReview", 0);
                po.counts.put("superseded", 0);
                po.counts.put("orphaned", 0);
                for (MatchLink l : file.links) {
                    if (!MatchStore.pairOf(l).equals(p)) continue;
                    po.counts.merge(l.status.name(), 1, Integer::sum);
                    MatchLinkDto dto = flags.toDto(l);
                    if (dto.orphanA || dto.orphanB) po.counts.merge("orphaned", 1, Integer::sum);
                    if (l.status == MatchStatus.PROPOSED) {
                        po.counts.merge(dto.superseded ? "superseded" : "toReview", 1, Integer::sum);
                    }
                }
                po.lastRun = file.runs.get(p.key());
                staleReasons(po, p, byName, hash, revoked);
                out.pairs.add(po);
            }
        }
        return out;
    }

    private void staleReasons(PairOverviewDto po, DataSourcePair p, Map<String, DataSourceCatalog.DataSourceInfo> sources,
                              String hash, Instant revoked) {
        if (!sources.containsKey(p.first()) || !sources.containsKey(p.second())) return;  // deleted source: nothing to run
        if (po.lastRun == null) {
            po.staleReasons.add("neverRun");
        } else {
            Instant ran = parseInstant(po.lastRun.startedAt);
            for (String s : List.of(p.first(), p.second())) {
                Instant changed = sources.get(s).lastChange();
                if (ran != null && changed != null && changed.isAfter(ran)) {
                    if (!po.staleReasons.contains("dataChanged")) po.staleReasons.add("dataChanged");
                }
            }
            if (hash != null && !hash.equals(po.lastRun.configurationHash)) po.staleReasons.add("configurationChanged");
            if (ran != null && revoked != null && revoked.isAfter(ran)) po.staleReasons.add("decisionsRevoked");
        }
        po.stale = !po.staleReasons.isEmpty();
    }

    // =========================
    //  Runs
    // =========================

    public List<MatchingRunSummaryDto> runMatching(MatchingRunRequest request) {
        if (request == null) throw new BadRequestException("Missing request body.");
        lock.lock();
        try {
            String project = requireProject();
            MatchingConfiguration configuration = configurationService.effectiveConfiguration();
            List<DataSourceCatalog.DataSourceInfo> sources = catalog.list();
            List<DataSourcePair> pairs = new ArrayList<>();
            if (Boolean.TRUE.equals(request.all)) {
                for (int i = 0; i < sources.size(); i++) {
                    for (int j = i + 1; j < sources.size(); j++) {
                        pairs.add(DataSourcePair.of(sources.get(i).shortName(), sources.get(j).shortName()));
                    }
                }
            } else {
                pairs.add(pairOf(request.dataSourceA, request.dataSourceB));
            }
            List<MatchingRunSummaryDto> out = new ArrayList<>();
            for (DataSourcePair pair : pairs) out.add(runPair(project, configuration, pair, sources));
            return out;
        } finally {
            lock.unlock();
        }
    }

    private MatchingRunSummaryDto runPair(String project, MatchingConfiguration configuration, DataSourcePair pair,
                                          List<DataSourceCatalog.DataSourceInfo> sources) {
        long started = System.currentTimeMillis();
        String startedAt = Instant.now().toString();
        MatchingFile file = store.read(project);
        Map<String, Set<String>> identical = identicalIriFinder.identicalIris();
        MatchClusters clusters = MatchClusters.build(file.links, identical);
        Map<String, IRI> graphs = graphs(sources);

        MatchingEngine.Result result;
        EntityProfiles first, second;
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            ValueResolver values = new ValueResolver(new RdfLinkedValueLookup(conn, graphs.values()));
            first = profiles(conn, pair.first(), graphs, configuration);
            second = profiles(conn, pair.second(), graphs, configuration);
            long identicalInPair = identical.values().stream()
                    .filter(s -> s.contains(pair.first()) && s.contains(pair.second())).count();
            result = new MatchingEngine(configuration, ontologyService::expandWithSubtypes, values)
                    .run(pair, first, second, file.links, clusters, (int) identicalInPair);
        }
        if (!project.equals(ProjectContext.getProjectName())) {
            throw new ConflictException("The project was changed during the matching run; nothing was saved.");
        }

        // replace the proposals of this pair, keep every decided link
        file.links.removeIf(l -> l.status == MatchStatus.PROPOSED && MatchStore.pairOf(l).equals(pair));
        Set<String> existing = file.links.stream().map(MatchStore::nodePairKey).collect(Collectors.toSet());
        String now = Instant.now().toString();
        for (PairScore s : result.proposals()) {
            MatchLink l = linkFrom(s, MatchStatus.PROPOSED, LinkOrigin.ALGORITHM);
            l.proposedAt = now;
            if (existing.add(MatchStore.nodePairKey(l))) file.links.add(l);
        }
        // refresh the snapshots of this pair's links
        for (MatchLink l : file.links) {
            if (!MatchStore.pairOf(l).equals(pair)) continue;
            refreshSnapshot(l.a, l.a.dataSource.equals(pair.first()) ? first : second);
            refreshSnapshot(l.b, l.b.dataSource.equals(pair.first()) ? first : second);
        }
        MatchingRunSummaryDto summary = result.summary();
        summary.startedAt = startedAt;
        summary.durationMs = System.currentTimeMillis() - started;
        file.runs.put(pair.key(), summary);
        store.write(project, file);
        return summary;
    }

    private static void refreshSnapshot(EntityNodeDto node, EntityProfiles profiles) {
        EntityProfile p = profiles.get(node.iri);
        if (p == null) return;
        node.label = p.label();
        node.types = new ArrayList<>(p.types());
    }

    private MatchLink linkFrom(PairScore s, MatchStatus status, LinkOrigin origin) {
        MatchLink l = new MatchLink();
        l.id = UUID.randomUUID().toString();
        l.a = snapshot(s.pair().a());
        l.b = snapshot(s.pair().b());
        l.status = status;
        l.origin = origin;
        l.typeGroupId = s.group().id();
        l.typeGroupLabel = s.group().label();
        l.score = s.score();
        l.comparedWeight = s.comparedWeight();
        l.evidence = new ArrayList<>(s.evidence());
        MatchStore.canonicalize(l);
        return l;
    }

    private static EntityNodeDto snapshot(EntityProfile p) {
        EntityNodeDto n = new EntityNodeDto(p.node().dataSource(), p.iri());
        n.label = p.label();
        n.types = new ArrayList<>(p.types());
        return n;
    }

    private EntityProfiles profiles(RepositoryConnection conn, String dataSource, Map<String, IRI> graphs,
                                    MatchingConfiguration configuration) {
        String key = ProjectContext.getProjectName() + "|" + configuration.hash();
        if (!key.equals(profileCacheKey)) {
            profileCache.clear();
            profileCacheKey = key;
        }
        IRI graph = graphs.get(dataSource);
        if (graph == null) throw new NotFoundException("Unknown data source: " + dataSource);
        return profileCache.computeIfAbsent(dataSource,
                ds -> EntityProfileLoader.loadProfiles(conn, ds, graph, configuration));
    }

    // =========================
    //  Links: listing
    // =========================

    public MatchLinkPageDto listLinks(String dataSourceA, String dataSourceB, List<MatchStatus> statuses, String typeGroupId,
                                      Double minScore, String q, boolean includeSuperseded, boolean includeOrphaned,
                                      int offset, int limit) {
        String project = requireProject();
        DataSourcePair pair = dataSourceA != null || dataSourceB != null ? pairOf(dataSourceA, dataSourceB) : null;
        MatchingFile file = store.read(project);
        MatchClusters clusters = clusters(file);
        String needle = q == null || q.isBlank() ? null : TextNormalizer.fold(q.trim());
        List<MatchLinkDto> items = new ArrayList<>();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            LinkFlags flags = new LinkFlags(clusters, new OrphanDetector(conn, graphs(catalog.list())),
                    clusters.conflicts(file.links), file.links, typeResolverOrNull());
            for (MatchLink l : file.links) {
                if (pair != null && !MatchStore.pairOf(l).equals(pair)) continue;
                if (statuses != null && !statuses.isEmpty() && !statuses.contains(l.status)) continue;
                if (typeGroupId != null && !typeGroupId.isBlank() && !typeGroupId.equals(l.typeGroupId)) continue;
                if (minScore != null && l.status == MatchStatus.PROPOSED && (l.score == null || l.score < minScore)) continue;
                if (needle != null && !matches(l, needle)) continue;
                MatchLinkDto dto = flags.toDto(l);
                if (!includeSuperseded && dto.superseded) continue;
                if (!includeOrphaned && (dto.orphanA || dto.orphanB)) continue;
                items.add(dto);
            }
        }
        items.sort(Comparator
                .comparing((MatchLinkDto d) -> d.score == null ? -1.0 : d.score, Comparator.reverseOrder())
                .thenComparing(d -> d.a.label == null ? d.a.iri : d.a.label)
                .thenComparing(d -> d.b.iri));
        MatchLinkPageDto page = new MatchLinkPageDto();
        page.total = items.size();
        int from = Math.max(0, Math.min(offset, items.size()));
        int to = Math.min(items.size(), from + Math.max(1, limit));
        page.items = new ArrayList<>(items.subList(from, to));
        return page;
    }

    private static boolean matches(MatchLink l, String needle) {
        for (String s : new String[]{l.a.label, l.b.label, l.a.iri, l.b.iri}) {
            if (s != null && TextNormalizer.fold(s).contains(needle)) return true;
        }
        return false;
    }

    // =========================
    //  Links: changes
    // =========================

    /** A change applied to the loaded file; returns the links it changed. */
    @FunctionalInterface
    private interface Mutation {
        List<MatchLink> apply(MatchingFile file, MatchClusters clusters, List<String> warnings);
    }

    public LinkChangeResultDto changeLinkStatus(String linkId, LinkAction action, String note, boolean dryRun) {
        if (action == null) throw new BadRequestException("Missing action.");
        return mutate(dryRun, (file, clusters, warnings) -> {
            MatchLink l = MatchStore.find(file, linkId).orElseThrow(() -> new NotFoundException("Link not found: " + linkId));
            if (note != null) l.note = note.isBlank() ? null : note;
            List<MatchLink> changed = applyAction(file, clusters, l, action);
            return changed;
        });
    }

    public LinkChangeResultDto changeLinkStatusBulk(List<String> linkIds, LinkAction action, boolean dryRun) {
        if (action == null) throw new BadRequestException("Missing action.");
        if (linkIds == null || linkIds.isEmpty()) throw new BadRequestException("No link selected.");
        return mutate(dryRun, (file, clusters, warnings) -> {
            List<MatchLink> changed = new ArrayList<>();
            MatchClusters current = clusters;
            for (String id : linkIds) {
                Optional<MatchLink> l = MatchStore.find(file, id);
                if (l.isEmpty()) {
                    warnings.add("Link not found: " + id);
                    continue;
                }
                try {
                    changed.addAll(applyAction(file, current, l.get(), action));
                    current = clusters(file);
                } catch (ConflictException e) {
                    warnings.add(describe(l.get()) + ": " + e.getMessage());
                }
            }
            return changed;
        });
    }

    public LinkChangeResultDto updateNote(String linkId, String note) {
        return mutate(false, (file, clusters, warnings) -> {
            MatchLink l = MatchStore.find(file, linkId).orElseThrow(() -> new NotFoundException("Link not found: " + linkId));
            l.note = note == null || note.isBlank() ? null : note;
            return List.of(l);
        });
    }

    private List<MatchLink> applyAction(MatchingFile file, MatchClusters clusters, MatchLink l, LinkAction action) {
        String now = Instant.now().toString();
        if (l.status == MatchStatus.INFERRED) {
            throw new ConflictException("This link is inferred from other links (" + chain(file, clusters, l)
                    + "); undo one of them instead.");
        }
        switch (action) {
            case ACCEPT -> {
                if (l.status.isConfirmed()) return List.of();
                List<String> violations = clusters.mergeViolations(EntityNode.of(l.a), EntityNode.of(l.b), file.links, l.id);
                if (!violations.isEmpty()) throw new ConflictException(String.join(" ", violations));
                l.status = l.origin == LinkOrigin.USER ? MatchStatus.MANUAL : MatchStatus.ACCEPTED;
                l.decidedAt = now;
                return List.of(l);
            }
            case REJECT -> {
                if (l.status == MatchStatus.REJECTED) return List.of();
                MatchStatus before = l.status;
                l.status = MatchStatus.REJECTED;
                if (before.isConfirmed()) {
                    MatchClusters after = clusters(file);
                    if (after.sameCluster(EntityNode.of(l.a), EntityNode.of(l.b))) {
                        l.status = before;
                        throw new ConflictException("These entities would remain linked through "
                                + describePath(file, after.path(EntityNode.of(l.a), EntityNode.of(l.b)))
                                + "; undo one of those links first.");
                    }
                    file.confirmedLinksRevokedAt = now;
                }
                l.decidedAt = now;
                return List.of(l);
            }
            case UNDO -> {
                if (l.status == MatchStatus.PROPOSED) throw new ConflictException("This proposal has not been reviewed: nothing to undo.");
                if (l.status.isConfirmed()) file.confirmedLinksRevokedAt = now;
                if (l.origin == LinkOrigin.USER) {
                    file.links.remove(l);
                } else {
                    l.status = MatchStatus.PROPOSED;
                    l.origin = LinkOrigin.ALGORITHM;
                    l.decidedAt = null;
                }
                return List.of(l);
            }
        }
        return List.of();
    }

    public LinkChangeResultDto createManualLink(CreateManualMatchRequest request, boolean dryRun) {
        if (request == null || request.a == null || request.b == null
                || request.a.iri == null || request.b.iri == null) {
            throw new BadRequestException("Two entities (data source and IRI) are required.");
        }
        Map<String, IRI> graphs = graphs(catalog.list());
        for (EntityNodeDto n : List.of(request.a, request.b)) {
            IRI graph = graphs.get(n.dataSource);
            if (graph == null) throw new BadRequestException("Unknown data source: " + n.dataSource);
            try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
                if (!conn.hasStatement(vf.createIRI(n.iri), null, null, false, graph)) {
                    throw new BadRequestException(n.iri + " has no statement in the data source " + n.dataSource + ".");
                }
            }
        }
        if (request.a.dataSource.equals(request.b.dataSource)) {
            throw new BadRequestException("A link joins entities of two different data sources.");
        }
        PairComparisonDto comparison = null;
        try {
            comparison = comparePair(request.a.dataSource, request.a.iri, request.b.dataSource, request.b.iri);
        } catch (InvalidConfigurationException e) {
            // no score without a usable configuration; the link can still be made
        }
        PairComparisonDto scored = comparison;
        return mutate(dryRun, (file, clusters, warnings) -> {
            EntityNode a = new EntityNode(request.a.dataSource, request.a.iri);
            EntityNode b = new EntityNode(request.b.dataSource, request.b.iri);
            Optional<MatchLink> existing = MatchStore.findByNodes(file, a, b);
            String now = Instant.now().toString();
            if (existing.isPresent()) {
                MatchLink l = existing.get();
                if (request.note != null && !request.note.isBlank()) l.note = request.note;
                switch (l.status) {
                    case ACCEPTED, MANUAL -> throw new ConflictException("These entities are already linked.");
                    case INFERRED -> throw new ConflictException("These entities are already linked by inference ("
                            + chain(file, clusters, l) + ").");
                    case PROPOSED -> {
                        return applyAction(file, clusters, l, LinkAction.ACCEPT);
                    }
                    case REJECTED -> {
                        List<String> violations = clusters.mergeViolations(a, b, file.links, l.id);
                        if (!violations.isEmpty()) throw new ConflictException(String.join(" ", violations));
                        l.status = MatchStatus.MANUAL;
                        l.origin = LinkOrigin.USER;
                        l.decidedAt = now;
                        return List.of(l);
                    }
                }
            }
            List<String> violations = clusters.mergeViolations(a, b, file.links, null);
            if (!violations.isEmpty()) throw new ConflictException(String.join(" ", violations));
            MatchLink l = new MatchLink();
            l.id = UUID.randomUUID().toString();
            l.a = scored != null ? scored.a.node : new EntityNodeDto(request.a.dataSource, request.a.iri);
            l.b = scored != null ? scored.b.node : new EntityNodeDto(request.b.dataSource, request.b.iri);
            l.status = MatchStatus.MANUAL;
            l.origin = LinkOrigin.USER;
            if (scored != null) {
                l.typeGroupId = scored.typeGroupId;
                l.typeGroupLabel = scored.typeGroupLabel;
                l.score = scored.score;
                l.comparedWeight = scored.comparedWeight;
                l.evidence = scored.evidence;
            }
            l.decidedAt = now;
            l.note = request.note == null || request.note.isBlank() ? null : request.note;
            MatchStore.canonicalize(l);
            file.links.add(l);
            return List.of(l);
        });
    }

    public LinkChangeResultDto removeOrphanedLinks(String pairKey, String dataSource, boolean dryRun) {
        DataSourcePair pair = pairKey == null || pairKey.isBlank() ? null : DataSourcePair.fromKey(pairKey);
        return mutate(dryRun, (file, clusters, warnings) -> {
            List<MatchLink> removed = new ArrayList<>();
            try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
                OrphanDetector orphans = new OrphanDetector(conn, graphs(catalog.list()));
                Iterator<MatchLink> it = file.links.iterator();
                while (it.hasNext()) {
                    MatchLink l = it.next();
                    if (l.status == MatchStatus.INFERRED) continue;      // the reconciliation handles them
                    if (pair != null && !MatchStore.pairOf(l).equals(pair)) continue;
                    if (dataSource != null && !dataSource.isBlank()
                            && !dataSource.equals(l.a.dataSource) && !dataSource.equals(l.b.dataSource)) continue;
                    if (orphans.isOrphan(EntityNode.of(l.a)) || orphans.isOrphan(EntityNode.of(l.b))) {
                        it.remove();
                        removed.add(l);
                        if (l.status.isConfirmed()) file.confirmedLinksRevokedAt = Instant.now().toString();
                    }
                }
            }
            return removed;
        });
    }

    private LinkChangeResultDto mutate(boolean dryRun, Mutation mutation) {
        lock.lock();
        try {
            String project = requireProject();
            MatchingFile file = store.read(project);
            Map<String, Set<String>> identical = identicalIriFinder.identicalIris();
            List<String> warnings = new ArrayList<>();
            List<MatchLink> changed = mutation.apply(file, MatchClusters.build(file.links, identical), warnings);

            MatchClusters after = MatchClusters.build(file.links, identical);
            MatchReconciler.Result r = MatchReconciler.reconcile(file, after);
            fillMissingSnapshots(r.added());

            LinkChangeResultDto out = new LinkChangeResultDto();
            out.warnings = warnings;
            try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
                List<ConflictDto> conflicts = after.conflicts(file.links);
                LinkFlags flags = new LinkFlags(after, new OrphanDetector(conn, graphs(catalog.list())),
                        conflicts, file.links, typeResolverOrNull());
                for (MatchLink l : changed) out.links.add(flags.toDto(l));
                for (MatchLink l : r.added()) out.inferredAdded.add(flags.toDto(l));
                for (MatchLink l : r.removed()) out.inferredRemoved.add(flags.toDto(l));
                for (MatchLinkDto d : out.links) {
                    if (d.typeMismatch) warnings.add(describe(d) + ": the two entities share no type group.");
                }
                if (!conflicts.isEmpty()) warnings.add(conflicts.size() + " conflict(s) between the links remain.");
            }
            if (!dryRun) {
                store.write(project, file);
                sameAsGraphWriter.rewrite(after);
            }
            out.applied = !dryRun;
            return out;
        } finally {
            lock.unlock();
        }
    }

    private void fillMissingSnapshots(List<MatchLink> links) {
        if (links.isEmpty()) return;
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            Map<String, IRI> graphs = graphs(catalog.list());
            RdfLinkedValueLookup lookup = new RdfLinkedValueLookup(conn, graphs.values());
            for (MatchLink l : links) {
                for (EntityNodeDto n : List.of(l.a, l.b)) {
                    if (n.label == null) n.label = lookup.textOf(n.iri).orElse(null);
                    if ((n.types == null || n.types.isEmpty()) && graphs.containsKey(n.dataSource)) {
                        n.types = new ArrayList<>();
                        try (RepositoryResult<Statement> st = conn.getStatements(vf.createIRI(n.iri),
                                org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, null, false, graphs.get(n.dataSource))) {
                            for (Statement s : st) n.types.add(s.getObject().stringValue());
                        }
                    }
                }
            }
        }
    }

    // =========================
    //  Comparison, search, clusters
    // =========================

    public PairComparisonDto comparePair(String dataSourceA, String iriA, String dataSourceB, String iriB) {
        String project = requireProject();
        if (iriA == null || iriB == null) throw new BadRequestException("Two IRIs are required.");
        List<DataSourceCatalog.DataSourceInfo> sources = catalog.list();
        Map<String, IRI> graphs = graphs(sources);
        if (!graphs.containsKey(dataSourceA)) throw new NotFoundException("Unknown data source: " + dataSourceA);
        if (!graphs.containsKey(dataSourceB)) throw new NotFoundException("Unknown data source: " + dataSourceB);

        MatchingConfiguration configuration = configurationService.effectiveConfiguration();
        Map<String, String> prefixes = new LinkedHashMap<>(MatchingConfigurationLoader.IMPLICIT_PREFIXES);
        prefixes.putAll(configuration.source().prefixes);
        MatchingFile file = store.read(project);
        MatchClusters clusters = clusters(file);
        PairComparisonDto out = new PairComparisonDto();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            RdfLinkedValueLookup lookup = new RdfLinkedValueLookup(conn, graphs.values());
            EntityProfile pa = EntityProfileLoader.loadProfile(conn, dataSourceA, graphs.get(dataSourceA), iriA, configuration);
            EntityProfile pb = EntityProfileLoader.loadProfile(conn, dataSourceB, graphs.get(dataSourceB), iriB, configuration);
            if (pa == null) throw new NotFoundException(iriA + " has no statement in " + dataSourceA + ".");
            if (pb == null) throw new NotFoundException(iriB + " has no statement in " + dataSourceB + ".");
            out.a = side(conn, pa, graphs.get(dataSourceA), prefixes, lookup);
            out.b = side(conn, pb, graphs.get(dataSourceB), prefixes, lookup);

            if (!dataSourceA.equals(dataSourceB)) {
                ValueResolver values = new ValueResolver(lookup);
                MatchingEngine engine = new MatchingEngine(configuration, ontologyService::expandWithSubtypes, values);
                Optional<TypeGroup> group = engine.types().sharedGroup(pa.types(), pb.types());
                if (group.isPresent()) {
                    boolean aFirst = dataSourceA.compareTo(dataSourceB) < 0;
                    EntityProfile first = aFirst ? pa : pb, second = aFirst ? pb : pa;
                    PairScore s = engine.scorer(clusters).scorePair(
                            new fr.cnrs.lacito.fieldarchive.services.matching.candidate.CandidatePair(first, second, group.get()));
                    out.typeGroupId = group.get().id();
                    out.typeGroupLabel = group.get().label();
                    out.score = s.score();
                    out.comparedWeight = s.comparedWeight();
                    out.outcome = s.outcome().name();
                    out.proposable = s.isProposable();
                    out.evidence = new ArrayList<>(s.evidence());
                    if (!aFirst) {                              // evidence values in the order A, B of the request
                        for (ConceptEvidenceDto e : out.evidence) {
                            String swap = e.valueA;
                            e.valueA = e.valueB;
                            e.valueB = swap;
                        }
                    }
                    for (MatchingConcept c : group.get().concepts()) {
                        List<String> inA = c.predicates().stream().filter(p -> !pa.valuesOf(p).isEmpty()).toList();
                        List<String> inB = c.predicates().stream().filter(p -> !pb.valuesOf(p).isEmpty()).toList();
                        if (!inA.isEmpty() || !inB.isEmpty()) out.conceptPredicates.put(c.key(), List.of(inA, inB));
                    }
                } else {
                    out.outcome = "NO_SHARED_GROUP";
                }
            }
        }

        EntityNode na = new EntityNode(dataSourceA, iriA), nb = new EntityNode(dataSourceB, iriB);
        if (dataSourceA.equals(dataSourceB)) {
            out.blockingReasons.add("Both entities belong to the data source \"" + dataSourceA + "\".");
        } else {
            Optional<MatchLink> existing = MatchStore.findByNodes(file, na, nb);
            existing.ifPresent(l -> out.existingLink = new LinkFlags(clusters, null, List.of(), file.links, null).toDto(l));
            if (existing.isPresent() && (existing.get().status.isConfirmed() || existing.get().status == MatchStatus.INFERRED)) {
                out.blockingReasons.add("These entities are already linked (" + existing.get().status.name().toLowerCase() + ").");
            } else {
                out.blockingReasons.addAll(clusters.mergeViolations(na, nb, file.links, existing.map(l -> l.id).orElse(null)));
            }
        }
        return out;
    }

    private static EntitySideDto side(RepositoryConnection conn, EntityProfile p, IRI graph, Map<String, String> prefixes,
                                      RdfLinkedValueLookup lookup) {
        EntitySideDto side = new EntitySideDto();
        side.node = snapshot(p);
        Map<String, PropertyValuesDto> byPredicate = new LinkedHashMap<>();
        try (RepositoryResult<Statement> st = conn.getStatements(vf.createIRI(p.iri()), null, null, false, graph)) {
            for (Statement s : st) {
                String predicate = s.getPredicate().stringValue();
                PropertyValuesDto pv = byPredicate.computeIfAbsent(predicate, k -> {
                    PropertyValuesDto x = new PropertyValuesDto();
                    x.predicate = k;
                    x.curie = MatchingConfigurationWriter.compact(k, prefixes);
                    return x;
                });
                pv.values.add(display(s.getObject(), prefixes, lookup));
            }
        }
        side.properties = new ArrayList<>(byPredicate.values());
        return side;
    }

    private static String display(Value v, Map<String, String> prefixes, RdfLinkedValueLookup lookup) {
        if (v instanceof Literal l) return l.getLabel();
        if (v instanceof IRI iri) {
            String compact = MatchingConfigurationWriter.compact(iri.stringValue(), prefixes);
            return lookup.textOf(iri.stringValue()).map(t -> t + " <" + compact + ">").orElse(compact);
        }
        return v.stringValue();
    }

    public List<MatchableEntityDto> searchEntities(String dataSource, String q, String typeGroupId, String unmatchedWith,
                                                   int limit) {
        lock.lock();
        try {
            String project = requireProject();
            MatchingConfiguration configuration = configurationService.effectiveConfiguration();
            Map<String, IRI> graphs = graphs(catalog.list());
            if (!graphs.containsKey(dataSource)) throw new NotFoundException("Unknown data source: " + dataSource);
            Map<String, String> prefixes = new LinkedHashMap<>(MatchingConfigurationLoader.IMPLICIT_PREFIXES);
            prefixes.putAll(configuration.source().prefixes);
            MatchClusters clusters = clusters(store.read(project));
            TypeGroupResolver types = new TypeGroupResolver(configuration, ontologyService::expandWithSubtypes);
            Optional<TypeGroup> group = typeGroupId == null || typeGroupId.isBlank() ? Optional.empty() : configuration.group(typeGroupId);
            String needle = q == null || q.isBlank() ? null : TextNormalizer.fold(q.trim());
            List<MatchableEntityDto> out = new ArrayList<>();
            try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
                for (EntityProfile p : profiles(conn, dataSource, graphs, configuration).all()) {
                    if (group.isPresent() && !types.groupsOf(p.types()).contains(group.get())) continue;
                    if (needle != null && !TextNormalizer.fold(p.displayName()).contains(needle)
                            && !TextNormalizer.fold(p.iri()).contains(needle)) continue;
                    if (unmatchedWith != null && !unmatchedWith.isBlank()
                            && clusters.memberIn(p.node(), unmatchedWith).isPresent()) continue;
                    MatchableEntityDto d = new MatchableEntityDto();
                    d.node = snapshot(p);
                    for (String t : p.types()) d.typeCuries.add(MatchingConfigurationWriter.compact(t, prefixes));
                    for (EntityNode m : clusters.clusterOf(p.node())) {
                        if (!m.equals(p.node())) d.matchedWith.add(m.toDto());
                    }
                    out.add(d);
                }
            }
            out.sort(Comparator.comparing((MatchableEntityDto d) -> d.node.label == null ? "￿" : TextNormalizer.fold(d.node.label))
                    .thenComparing(d -> d.node.iri));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        } finally {
            lock.unlock();
        }
    }

    public List<MatchClusterDto> getClusters(int minSize) {
        String project = requireProject();
        MatchingFile file = store.read(project);
        MatchClusters clusters = clusters(file);
        List<ConflictDto> conflicts = clusters.conflicts(file.links);
        Map<EntityNode, EntityNodeDto> snapshots = snapshots(file);
        List<MatchClusterDto> out = new ArrayList<>();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            LinkFlags flags = new LinkFlags(clusters, new OrphanDetector(conn, graphs(catalog.list())), conflicts,
                    file.links, null);
            for (Set<EntityNode> cluster : clusters.clusters(Math.max(2, minSize))) {
                MatchClusterDto c = new MatchClusterDto();
                List<EntityNode> nodes = new ArrayList<>(cluster);
                nodes.sort(Comparator.comparing(EntityNode::dataSource).thenComparing(EntityNode::iri));
                c.id = nodes.get(0).toString();
                for (EntityNode n : nodes) c.nodes.add(snapshots.getOrDefault(n, n.toDto()));
                for (MatchLink l : file.links) {
                    if (cluster.contains(EntityNode.of(l.a)) && cluster.contains(EntityNode.of(l.b))) {
                        MatchLinkDto d = flags.toDto(l);
                        c.links.add(d);
                        c.conflict |= d.conflict;
                    }
                }
                out.add(c);
            }
        }
        out.sort(Comparator.comparing((MatchClusterDto c) -> !c.conflict).thenComparing(c -> -c.nodes.size()));
        return out;
    }

    // =========================
    //  Read-time flags
    // =========================

    /** Computes the derived flags of links against one snapshot of the clusters. */
    private final class LinkFlags {
        private final MatchClusters clusters;
        private final OrphanDetector orphans;
        private final Set<String> conflictLinkIds = new HashSet<>();
        private final List<MatchLink> rejected;
        private final Map<String, MatchLink> byId = new HashMap<>();
        private final Map<EntityNode, EntityNodeDto> snapshots;
        private final TypeGroupResolver types;

        LinkFlags(MatchClusters clusters, OrphanDetector orphans, List<ConflictDto> conflicts, List<MatchLink> links,
                  TypeGroupResolver types) {
            this.clusters = clusters;
            this.orphans = orphans;
            this.types = types;
            for (ConflictDto c : conflicts) conflictLinkIds.addAll(c.linkIds);
            this.rejected = links.stream().filter(l -> l.status == MatchStatus.REJECTED).toList();
            for (MatchLink l : links) byId.put(l.id, l);
            MatchingFile f = new MatchingFile();
            f.links = links;
            this.snapshots = snapshots(f);
        }

        MatchLinkDto toDto(MatchLink l) {
            MatchLinkDto d = LinkDtoCopy.toDto(l);
            EntityNode a = EntityNode.of(l.a), b = EntityNode.of(l.b);
            if (l.status == MatchStatus.PROPOSED) {
                d.superseded = clusters.memberIn(a, b.dataSource()).filter(m -> !m.equals(b)).isPresent()
                        || clusters.memberIn(b, a.dataSource()).filter(m -> !m.equals(a)).isPresent()
                        || (clusters.clusterOf(a).size() > 1 || clusters.clusterOf(b).size() > 1)
                        && !clusters.mergeViolations(a, b, rejected, null).isEmpty();
            }
            if (orphans != null) {
                d.orphanA = orphans.isOrphan(a);
                d.orphanB = orphans.isOrphan(b);
            }
            if (types != null && l.status.isConfirmed() && l.a.types != null && l.b.types != null
                    && !l.a.types.isEmpty() && !l.b.types.isEmpty()) {
                d.typeMismatch = types.sharedGroup(new HashSet<>(l.a.types), new HashSet<>(l.b.types)).isEmpty();
            }
            d.conflict = conflictLinkIds.contains(l.id);
            if (l.status == MatchStatus.INFERRED) {
                for (MatchClusters.Step s : clusters.path(a, b)) {
                    MatchLinkDto.InferenceStepDto step = new MatchLinkDto.InferenceStepDto();
                    step.kind = s.isIdentical() ? "identical" : "link";
                    if (!s.isIdentical()) {
                        step.linkId = s.edgeId();
                        MatchLink via = byId.get(s.edgeId());
                        step.status = via != null ? via.status : null;
                    }
                    step.from = snapshots.getOrDefault(s.from(), s.from().toDto());
                    step.to = snapshots.getOrDefault(s.to(), s.to().toDto());
                    d.inferenceSteps.add(step);
                }
            }
            return d;
        }
    }

    // =========================
    //  Helpers
    // =========================

    private String chain(MatchingFile file, MatchClusters clusters, MatchLink l) {
        return describePath(file, clusters.path(EntityNode.of(l.a), EntityNode.of(l.b)));
    }

    private static String describePath(MatchingFile file, List<MatchClusters.Step> path) {
        Map<EntityNode, EntityNodeDto> snapshots = snapshots(file);
        StringBuilder sb = new StringBuilder();
        for (MatchClusters.Step s : path) {
            if (sb.length() == 0) sb.append(name(snapshots, s.from()));
            sb.append(s.isIdentical() ? " = " : " ≡ ").append(name(snapshots, s.to()));
        }
        return sb.toString();
    }

    private static String name(Map<EntityNode, EntityNodeDto> snapshots, EntityNode n) {
        EntityNodeDto s = snapshots.get(n);
        return n.dataSource() + ":" + (s != null && s.label != null ? s.label : n.iri());
    }

    private static String describe(MatchLink l) {
        return (l.a.label != null ? l.a.label : l.a.iri) + " / " + (l.b.label != null ? l.b.label : l.b.iri);
    }

    private static Map<EntityNode, EntityNodeDto> snapshots(MatchingFile file) {
        Map<EntityNode, EntityNodeDto> out = new HashMap<>();
        for (MatchLink l : file.links) {
            out.merge(EntityNode.of(l.a), l.a, (x, y) -> x.label != null ? x : y);
            out.merge(EntityNode.of(l.b), l.b, (x, y) -> x.label != null ? x : y);
        }
        return out;
    }

    private MatchClusters clusters(MatchingFile file) {
        return MatchClusters.build(file.links, identicalIriFinder.identicalIris());
    }

    private TypeGroupResolver typeResolverOrNull() {
        try {
            return new TypeGroupResolver(configurationService.effectiveConfiguration(), ontologyService::expandWithSubtypes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private DataSourcePair pairOf(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            throw new BadRequestException("Two data sources are required.");
        }
        if (a.equals(b)) throw new BadRequestException("A data source is not matched against itself.");
        return DataSourcePair.of(a, b);
    }

    private static Map<String, IRI> graphs(List<DataSourceCatalog.DataSourceInfo> sources) {
        Map<String, IRI> out = new LinkedHashMap<>();
        for (DataSourceCatalog.DataSourceInfo s : sources) out.put(s.shortName(), s.graph());
        return out;
    }

    private static int countTypedEntities(RepositoryConnection conn, IRI graph) {
        String q = "SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE { GRAPH <" + graph.stringValue() + "> { ?s a ?t } FILTER(isIRI(?s)) }";
        try (TupleQueryResult r = conn.prepareTupleQuery(q).evaluate()) {
            if (r.hasNext()) {
                BindingSet bs = r.next();
                return bs.getValue("n") instanceof Literal l ? l.intValue() : 0;
            }
        }
        return 0;
    }

    private static Instant parseInstant(String s) {
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String requireProject() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("No project is open.");
        return ProjectContext.getProjectName();
    }

    /** Copies a stored link into its read-time DTO. */
    static final class LinkDtoCopy {
        static MatchLinkDto toDto(MatchLink l) {
            MatchLinkDto d = new MatchLinkDto();
            d.id = l.id;
            d.a = l.a;
            d.b = l.b;
            d.status = l.status;
            d.origin = l.origin;
            d.typeGroupId = l.typeGroupId;
            d.typeGroupLabel = l.typeGroupLabel;
            d.score = l.score;
            d.comparedWeight = l.comparedWeight;
            d.evidence = l.evidence;
            d.inferredFrom = l.inferredFrom;
            d.proposedAt = l.proposedAt;
            d.decidedAt = l.decidedAt;
            d.note = l.note;
            return d;
        }
    }
}
