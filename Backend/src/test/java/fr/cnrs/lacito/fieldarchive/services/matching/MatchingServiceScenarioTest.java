package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLinkDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchStatus;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDtos.*;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingRunSummaryDto;
import fr.cnrs.lacito.fieldarchive.exception.ConflictException;
import fr.cnrs.lacito.fieldarchive.services.OntologyService;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationService;
import fr.cnrs.lacito.fieldarchive.utils.ProjectsDirectory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * The matching on the notebook's samples ({@code fixture.trig}, converted from the CSVs, plus
 * a third source "archive"), with the decisions of the CSVs ({@code fixture-decisions.json}).
 */
class MatchingServiceScenarioTest {

    static final String PROJECT = "fixture";
    static OntologyService ontologyService;

    @TempDir
    Path dataDir;

    MatchingService service;
    IdenticalIriFinder identicalIriFinder;
    MatchingConfigurationService configurationService;

    @BeforeAll
    static void loadOntologies() {
        ontologyService = new OntologyService();
    }

    @BeforeEach
    void openFixture() throws Exception {
        SailRepository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection conn = repo.getConnection();
             InputStream in = getClass().getResourceAsStream("/matching/fixture.trig")) {
            conn.add(in, "", RDFFormat.TRIG);
        }
        ProjectContext.set(repo, PROJECT);

        ProjectsDirectory projects = new ProjectsDirectory() {
            @Override
            public Path getPublicPath() {
                return dataDir;
            }
        };
        Path projectDir = Files.createDirectories(dataDir.resolve(PROJECT));
        for (String[] f : new String[][]{{"fixture-decisions.json", MatchStore.FILE_NAME},
                {"fixture-configuration.json", MatchingConfigurationService.FILE_NAME}}) {
            try (InputStream in = getClass().getResourceAsStream("/matching/" + f[0])) {
                Files.copy(in, projectDir.resolve(f[1]));
            }
        }
        DataSourceCatalog catalog = new DataSourceCatalog();
        identicalIriFinder = new IdenticalIriFinder(catalog);
        configurationService = new MatchingConfigurationService(projects);
        service = new MatchingService(new MatchStore(projects), configurationService, catalog, identicalIriFinder,
                new SameAsGraphWriter(), ontologyService);
    }

    @AfterEach
    void close() {
        ProjectContext.close();
    }

    // =========================
    //  Helpers
    // =========================

    MatchingRunSummaryDto run(String a, String b) {
        MatchingRunRequest r = new MatchingRunRequest();
        r.dataSourceA = a;
        r.dataSourceB = b;
        return service.runMatching(r).get(0);
    }

    List<MatchLinkDto> links(String a, String b, MatchStatus... statuses) {
        return service.listLinks(a, b, List.of(statuses), null, null, null, true, true, 0, 1000).items;
    }

    /** "iriA|iriB" → link, for the PROPOSED links of a pair. */
    Map<String, MatchLinkDto> proposals(String a, String b) {
        return links(a, b, MatchStatus.PROPOSED).stream()
                .collect(Collectors.toMap(l -> local(l.a.iri) + "|" + local(l.b.iri), Function.identity()));
    }

    static String local(String iri) {
        return iri.substring(iri.lastIndexOf('/') + 1);
    }

    MatchLinkDto proposal(String a, String b, String key) {
        MatchLinkDto l = proposals(a, b).get(key);
        assertThat(l).as("proposal " + key).isNotNull();
        return l;
    }

    void act(MatchLinkDto l, LinkAction action) {
        service.changeLinkStatus(l.id, action, null, false);
    }

    long sameAsTriples() {
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            return conn.size(SimpleValueFactory.getInstance().createIRI(RdfContexts.CTX_MATCHING));
        }
    }

    // =========================
    //  §12.2 expected results on the samples
    // =========================

    @Test
    void theSamplesGiveTheExpectedProposals() {
        MatchingRunSummaryDto summary = run("tropy", "gramps");
        Map<String, MatchLinkDto> p = proposals("gramps", "tropy");

        assertThat(p.keySet()).containsExactlyInAnyOrder("P002|item2", "CB001|item5", "PL002|item4");
        assertThat(p.get("P002|item2").score).isCloseTo(0.9178, within(0.0001));   // (3·0.963 + 2·0.85) / 5
        assertThat(p.get("CB001|item5").score).isEqualTo(1.0);                      // Musee / Musée d'Orsay
        assertThat(p.get("PL002|item4").score).isEqualTo(1.0);                      // best of "Lyon", "Lyon France"
        assertThat(p.get("P002|item2").evidence).extracting(e -> e.concept).containsExactly("name", "birthDate");

        assertThat(summary.dataSourceA).isEqualTo("gramps");
        assertThat(summary.identicalIriCount).isEqualTo(1);                          // item1
        assertThat(summary.excludedMatchedA).isEqualTo(2);                           // P001 (confirmed), item1 (identical)
        assertThat(summary.excludedMatchedB).isEqualTo(2);                           // item3, item1
        assertThat(summary.proposalCount).isEqualTo(3);
        assertThat(summary.insufficientEvidenceCount).isPositive();                  // item8 / item9: no name

        // determinism: a rerun gives the same proposals
        run("gramps", "tropy");
        assertThat(proposals("gramps", "tropy").keySet()).isEqualTo(p.keySet());
    }

    // =========================
    //  §12.3 scenarios
    // =========================

    @Test
    void acceptedLinksSurviveARerunAndAreNotProposedAgain() {
        run("gramps", "tropy");
        act(proposal("gramps", "tropy", "P002|item2"), LinkAction.ACCEPT);
        run("gramps", "tropy");
        assertThat(proposals("gramps", "tropy")).doesNotContainKey("P002|item2");
        assertThat(links("gramps", "tropy", MatchStatus.ACCEPTED)).extracting(l -> local(l.a.iri)).contains("P002", "P001");
    }

    @Test
    void rejectedPairsAreNotProposedAgain() {
        run("gramps", "tropy");
        act(proposal("gramps", "tropy", "PL002|item4"), LinkAction.REJECT);
        run("gramps", "tropy");
        assertThat(proposals("gramps", "tropy")).doesNotContainKey("PL002|item4");
    }

    @Test
    void transitivityCreatesInferredLinksAndUndoRemovesThem() {
        run("gramps", "tropy");
        act(proposal("gramps", "tropy", "P002|item2"), LinkAction.ACCEPT);
        run("archive", "gramps");
        MatchLinkDto a1p002 = proposal("archive", "gramps", "A1|P002");
        assertThat(a1p002.score).isCloseTo(0.94, within(0.0001));
        LinkChangeResultDto r = service.changeLinkStatus(a1p002.id, LinkAction.ACCEPT, null, false);

        assertThat(r.inferredAdded).hasSize(1);
        MatchLinkDto inferred = r.inferredAdded.get(0);
        assertThat(local(inferred.a.iri) + "|" + local(inferred.b.iri)).isEqualTo("A1|item2");
        assertThat(inferred.status).isEqualTo(MatchStatus.INFERRED);
        assertThat(inferred.inferredFrom).hasSize(2);
        assertThat(inferred.inferenceSteps).hasSize(2);

        run("archive", "tropy");
        assertThat(proposals("archive", "tropy").keySet()).noneMatch(k -> k.contains("A1") || k.contains("item2"));
        assertThat(sameAsTriples()).isEqualTo(8);          // 6 among {item2, P002, A1} + 2 for item3/P001

        // the user cannot act on an inferred link
        assertThatThrownBy(() -> act(inferred, LinkAction.REJECT)).isInstanceOf(ConflictException.class);

        // undo: dry run first, then apply
        LinkChangeResultDto dry = service.changeLinkStatus(a1p002.id, LinkAction.UNDO, null, true);
        assertThat(dry.applied).isFalse();
        assertThat(dry.inferredRemoved).hasSize(1);
        assertThat(links("archive", "tropy", MatchStatus.INFERRED)).hasSize(1);   // nothing changed yet
        service.changeLinkStatus(a1p002.id, LinkAction.UNDO, null, false);
        assertThat(links("archive", "tropy", MatchStatus.INFERRED)).isEmpty();
        assertThat(sameAsTriples()).isEqualTo(4);

        PairOverviewDto archiveTropy = service.getOverview().pairs.stream()
                .filter(p -> p.key.equals("archive⇄tropy")).findFirst().orElseThrow();
        assertThat(archiveTropy.staleReasons).contains("decisionsRevoked");
    }

    @Test
    void aSourceHasAtMostOneCounterpart() {                 // invariant I1
        run("gramps", "tropy");
        act(proposal("gramps", "tropy", "P002|item2"), LinkAction.ACCEPT);
        CreateManualMatchRequest m = new CreateManualMatchRequest();
        m.a = new fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto("tropy", "http://tropy.local/item6");
        m.b = new fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto("gramps", "http://gramps.local/P002");
        assertThatThrownBy(() -> service.createManualLink(m, false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("same data source");
    }

    @Test
    void aRejectionCannotBeBypassedThroughAThirdSource() {  // invariant I2
        run("gramps", "tropy");
        act(proposal("gramps", "tropy", "PL002|item4"), LinkAction.REJECT);
        run("archive", "gramps");
        run("archive", "tropy");
        MatchLinkDto a2item4 = proposal("archive", "tropy", "A2|item4");
        act(proposal("archive", "gramps", "A2|PL002"), LinkAction.ACCEPT);
        assertThatThrownBy(() -> act(a2item4, LinkAction.ACCEPT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("rejected");
        // and the blocked proposal is shown as superseded
        assertThat(links("archive", "tropy", MatchStatus.PROPOSED).stream()
                .filter(l -> l.id.equals(a2item4.id)).findFirst().orElseThrow().superseded).isTrue();
    }

    @Test
    void aProposalImpliedByOtherLinksBecomesInferred() {
        run("gramps", "tropy");
        run("archive", "tropy");
        run("archive", "gramps");
        MatchLinkDto a1item2 = proposal("archive", "tropy", "A1|item2");
        act(proposal("gramps", "tropy", "P002|item2"), LinkAction.ACCEPT);
        act(proposal("archive", "gramps", "A1|P002"), LinkAction.ACCEPT);
        MatchLinkDto now = links("archive", "tropy", MatchStatus.INFERRED).get(0);
        assertThat(now.id).isEqualTo(a1item2.id);
        assertThat(now.evidence).isNotEmpty();                // the evidence of the proposal is kept
    }

    @Test
    void manualLinksAndUndo() {
        CreateManualMatchRequest m = new CreateManualMatchRequest();
        m.a = new fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto("tropy", "http://tropy.local/item6");
        m.b = new fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto("gramps", "http://gramps.local/P003");
        LinkChangeResultDto r = service.createManualLink(m, false);
        MatchLinkDto manual = r.links.get(0);
        assertThat(manual.status).isEqualTo(MatchStatus.MANUAL);
        assertThat(manual.a.dataSource).isEqualTo("gramps");     // canonical order
        assertThat(manual.typeGroupId).isEqualTo("person");
        service.changeLinkStatus(manual.id, LinkAction.UNDO, null, false);
        assertThat(links("gramps", "tropy", MatchStatus.MANUAL)).isEmpty();
    }

    @Test
    void orphanedLinksAreFlaggedThenRemoved() {
        run("gramps", "tropy");
        act(proposal("gramps", "tropy", "P002|item2"), LinkAction.ACCEPT);
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            var vf = SimpleValueFactory.getInstance();
            conn.remove(vf.createIRI("http://gramps.local/P002"), null, null, vf.createIRI("urn:datasource:gramps"));
        }
        identicalIriFinder.onDataChanged(new ProjectDataChangedEvent(PROJECT));
        service.onDataChanged(new ProjectDataChangedEvent(PROJECT));
        MatchLinkDto accepted = links("gramps", "tropy", MatchStatus.ACCEPTED).stream()
                .filter(l -> local(l.a.iri).equals("P002")).findFirst().orElseThrow();
        assertThat(accepted.orphanA).isTrue();
        LinkChangeResultDto r = service.removeOrphanedLinks("gramps⇄tropy", null, false);
        assertThat(r.links).extracting(l -> l.id).contains(accepted.id);
        assertThat(links("gramps", "tropy", MatchStatus.ACCEPTED)).noneMatch(l -> l.id.equals(accepted.id));
    }

    @Test
    void comparingTwoEntitiesExplainsTheScore() {
        PairComparisonDto c = service.comparePair("tropy", "http://tropy.local/item2", "gramps", "http://gramps.local/P002");
        assertThat(c.typeGroupId).isEqualTo("person");
        assertThat(c.proposable).isTrue();
        assertThat(c.evidence.get(0).valueA).isEqualTo("Marie Lefebvre");     // in the order of the request
        assertThat(c.conceptPredicates.get("name").get(0)).containsExactly("https://www.ica.org/standards/RiC/ontology#name");
        assertThat(c.blockingReasons).isEmpty();
    }

    @Test
    void aConfigurationChangeMakesPairsStale() {
        run("gramps", "tropy");
        PairOverviewDto before = service.getOverview().pairs.stream().filter(p -> p.key.equals("gramps⇄tropy")).findFirst().orElseThrow();
        assertThat(before.staleReasons).doesNotContain("configurationChanged");
        var dto = configurationService.currentConfiguration();
        dto.defaults.threshold = 0.8;
        configurationService.save(dto);
        PairOverviewDto after = service.getOverview().pairs.stream().filter(p -> p.key.equals("gramps⇄tropy")).findFirst().orElseThrow();
        assertThat(after.staleReasons).contains("configurationChanged");
    }
}
