package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchStatus;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDtos.*;
import fr.cnrs.lacito.fieldarchive.dtos.matching.ValidationReportDto;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.InvalidConfigurationException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import fr.cnrs.lacito.fieldarchive.services.OntologyService;
import fr.cnrs.lacito.fieldarchive.services.matching.candidate.CandidatePair;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.DataSourcePair;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.MatchClusters;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfiguration;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationLoader;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationValidator;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingVocabularyService;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.*;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.PairScore;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Runs the algorithm with an unsaved configuration on one pair of sources, writing nothing,
 * and compares the outcome with the stored proposals and with the user's past decisions.
 */
@Service
public class MatchingPreviewService {

    private static final int MAX_SAMPLES = 20;

    private final MatchStore store;
    private final DataSourceCatalog catalog;
    private final IdenticalIriFinder identicalIriFinder;
    private final OntologyService ontologyService;
    private final MatchingVocabularyService vocabularyService;

    public MatchingPreviewService(MatchStore store, DataSourceCatalog catalog, IdenticalIriFinder identicalIriFinder,
                                  OntologyService ontologyService, MatchingVocabularyService vocabularyService) {
        this.store = store;
        this.catalog = catalog;
        this.identicalIriFinder = identicalIriFinder;
        this.ontologyService = ontologyService;
        this.vocabularyService = vocabularyService;
    }

    public MatchingPreviewDto preview(MatchingPreviewRequest request) {
        if (!ProjectContext.isOpen()) throw new BadRequestException("No project is open.");
        if (request == null || request.configuration == null) throw new BadRequestException("A configuration is required.");
        if (request.dataSourceA == null || request.dataSourceB == null || request.dataSourceA.equals(request.dataSourceB)) {
            throw new BadRequestException("Two different data sources are required.");
        }
        String project = ProjectContext.getProjectName();
        MatchingConfigurationDto dto = MatchingConfigurationLoader.normalize(MatchingConfigurationLoader.copy(request.configuration));
        ValidationReportDto report = MatchingConfigurationValidator.validate(dto, vocabularyService.dataContext());
        if (report.hasErrors()) throw new InvalidConfigurationException("The configuration has errors.", report);
        MatchingConfiguration configuration = MatchingConfigurationLoader.compile(dto);

        DataSourcePair pair = DataSourcePair.of(request.dataSourceA, request.dataSourceB);
        Map<String, IRI> graphs = new LinkedHashMap<>();
        for (DataSourceCatalog.DataSourceInfo s : catalog.list()) graphs.put(s.shortName(), s.graph());
        for (String s : List.of(pair.first(), pair.second())) {
            if (!graphs.containsKey(s)) throw new NotFoundException("Unknown data source: " + s);
        }
        MatchingFile file = store.read(project);
        Map<String, Set<String>> identical = identicalIriFinder.identicalIris();
        MatchClusters clusters = MatchClusters.build(file.links, identical);

        MatchingPreviewDto out = new MatchingPreviewDto();
        out.validation = report;
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            ValueResolver values = new ValueResolver(new RdfLinkedValueLookup(conn, graphs.values()));
            EntityProfiles first = EntityProfileLoader.loadProfiles(conn, pair.first(), graphs.get(pair.first()), configuration);
            EntityProfiles second = EntityProfileLoader.loadProfiles(conn, pair.second(), graphs.get(pair.second()), configuration);
            long identicalInPair = identical.values().stream()
                    .filter(s -> s.contains(pair.first()) && s.contains(pair.second())).count();
            MatchingEngine engine = new MatchingEngine(configuration, ontologyService::expandWithSubtypes, values);
            MatchingEngine.Result result = engine.run(pair, first, second, file.links, clusters, (int) identicalInPair);
            out.summary = result.summary();
            for (PairScore s : result.scores()) {
                int bin = Math.min(19, (int) Math.floor(s.score() * 20));
                out.scoreHistogram[bin]++;
            }
            out.proposalDiff = diff(file, pair, result.proposals());
            out.decisionAgreement = agreement(file, pair, first, second, engine, clusters);
        }
        return out;
    }

    private static ProposalDiffDto diff(MatchingFile file, DataSourcePair pair, List<PairScore> proposals) {
        ProposalDiffDto d = new ProposalDiffDto();
        Map<String, MatchLink> current = new LinkedHashMap<>();
        for (MatchLink l : file.links) {
            if (l.status == MatchStatus.PROPOSED && MatchStore.pairOf(l).equals(pair)) current.put(l.a.iri + "\u0000" + l.b.iri, l);
        }
        Set<String> next = new HashSet<>();
        for (PairScore s : proposals) {
            String key = s.pair().a().iri() + "\u0000" + s.pair().b().iri();
            next.add(key);
            if (current.containsKey(key)) {
                d.unchanged++;
            } else {
                d.added++;
                if (d.samplesAdded.size() < MAX_SAMPLES) d.samplesAdded.add(sample(s, null));
            }
        }
        for (Map.Entry<String, MatchLink> e : current.entrySet()) {
            if (next.contains(e.getKey())) continue;
            d.removed++;
            if (d.samplesRemoved.size() < MAX_SAMPLES) {
                MatchLink l = e.getValue();
                PreviewSampleDto p = new PreviewSampleDto();
                p.a = l.a;
                p.b = l.b;
                p.score = l.score != null ? l.score : 0;
                p.typeGroupLabel = l.typeGroupLabel;
                p.status = l.status;
                d.samplesRemoved.add(p);
            }
        }
        return d;
    }

    /** Rescores the decided links of the pair, ignoring the exclusions, as a check against ground truth. */
    private static DecisionAgreementDto agreement(MatchingFile file, DataSourcePair pair, EntityProfiles first,
                                                  EntityProfiles second, MatchingEngine engine, MatchClusters clusters) {
        DecisionAgreementDto d = new DecisionAgreementDto();
        List<PreviewSampleDto> surprising = new ArrayList<>();
        for (MatchLink l : file.links) {
            boolean confirmed = l.status.isConfirmed();
            if (!confirmed && l.status != MatchStatus.REJECTED) continue;
            if (!MatchStore.pairOf(l).equals(pair)) continue;
            EntityProfile a = first.get(l.a.iri), b = second.get(l.b.iri);
            boolean above = false;
            double score = 0;
            String group = null;
            if (a != null && b != null) {
                Optional<TypeGroup> g = engine.types().sharedGroup(a.types(), b.types());
                if (g.isPresent()) {
                    PairScore s = engine.scorer(clusters).scorePair(new CandidatePair(a, b, g.get()));
                    above = s.isProposable();
                    score = s.score();
                    group = g.get().label();
                }
            }
            if (confirmed) {
                if (above) d.confirmedAbove++; else d.confirmedBelow++;
            } else {
                if (above) d.rejectedAbove++; else d.rejectedBelow++;
            }
            if (confirmed != above && surprising.size() < MAX_SAMPLES) {
                PreviewSampleDto p = new PreviewSampleDto();
                p.a = l.a;
                p.b = l.b;
                p.score = score;
                p.typeGroupLabel = group;
                p.status = l.status;
                surprising.add(p);
            }
        }
        d.samples = surprising;
        return d;
    }

    private static PreviewSampleDto sample(PairScore s, MatchStatus status) {
        PreviewSampleDto p = new PreviewSampleDto();
        p.a = node(s.pair().a());
        p.b = node(s.pair().b());
        p.score = s.score();
        p.typeGroupLabel = s.group().label();
        p.status = status;
        return p;
    }

    private static EntityNodeDto node(EntityProfile p) {
        EntityNodeDto n = new EntityNodeDto(p.node().dataSource(), p.iri());
        n.label = p.label();
        n.types = new ArrayList<>(p.types());
        return n;
    }
}
