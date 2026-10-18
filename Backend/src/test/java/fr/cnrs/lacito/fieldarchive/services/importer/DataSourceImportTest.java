package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.ProjectDataChangedEvent;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.CreateExternalDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.dtos.DataSourceDto;
import fr.cnrs.lacito.fieldarchive.dtos.ImportResult;
import fr.cnrs.lacito.fieldarchive.dtos.UpdateDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.ConflictException;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService.ImportRequest;
import fr.cnrs.lacito.fieldarchive.services.ProjectService;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Imports into external data sources with the real lameta plugin (the jar copied by the build
 * into target/ricImporterPlugins) and with RDF files, on an in-memory store.
 */
class DataSourceImportTest {

    static final String PROJECT = "demo";
    static final String PREFIX = "http://example.org/demo";
    static final ValueFactory vf = SimpleValueFactory.getInstance();

    @TempDir
    Path tmp;

    SailRepository repo;
    ImporterRegistry registry;
    DataSourceService service;
    final List<Object> events = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(vf.createIRI(RdfNamespaces.APP + "/projects#" + PROJECT), vf.createIRI(RdfNamespaces.APP, "prefix"),
                    vf.createLiteral(PREFIX), vf.createIRI(RdfNamespaces.APP + "/projects#" + PROJECT + "/metadata"));
        }
        ProjectContext.set(repo, PROJECT);

        Path bundled = ImporterRegistry.defaultBundledDirectory();
        assertThat(bundled).as("target/ricImporterPlugins, filled by the build").isNotNull();
        registry = new ImporterRegistry(bundled, tmp.resolve("user-plugins"));
        service = new DataSourceService(new ProjectService(null, null, null, events::add), registry, events::add);
    }

    @AfterEach
    void close() {
        ProjectContext.close();
        registry.close();
    }

    static Path lametaProject() throws Exception {
        return Path.of(DataSourceImportTest.class.getResource("/importer/lameta/Test2").toURI());
    }

    private DataSourceDto createLameta(String shortName, Path input) {
        CreateExternalDataSourceRequest req = new CreateExternalDataSourceRequest();
        req.setShortName(shortName);
        req.setName("Lameta recordings");
        req.setImporter("LAMETA"); // matched ignoring case, stored as "lameta"
        req.setSourceLocation(input.toString());
        service.createExternalDataSource(req, null);
        return service.getDataSource(shortName);
    }

    private Set<Resource> subjects(IRI graph) {
        Set<Resource> out = new HashSet<>();
        try (RepositoryConnection conn = repo.getConnection(); var st = conn.getStatements(null, null, null, graph)) {
            st.forEach(s -> out.add(s.getSubject()));
        }
        return out;
    }

    private long size(IRI graph) {
        try (RepositoryConnection conn = repo.getConnection()) {
            return conn.size(graph);
        }
    }

    @Test
    void theBundledLametaPluginIsFound() {
        assertThat(registry.find("lameta")).get().satisfies(i -> {
            assertThat(i.origin()).isEqualTo(ImporterOrigin.BUNDLED);
            assertThat(i.inputKind().name()).isEqualTo("DIRECTORY");
            assertThat(i.version()).isNotBlank();
        });
        assertThat(registry.snapshot().importers()).last().extracting(Importer::id).isEqualTo(RdfFileImporter.ID);
    }

    @Test
    void importsALametaProjectStraightIntoTheSourceGraph() throws Exception {
        DataSourceDto ds = createLameta("lameta-src", lametaProject());
        IRI graph = vf.createIRI(ds.graphIri);

        assertThat(ds.importer).isEqualTo("lameta");
        assertThat(ds.importerStatus).isEqualTo("PLUGIN");
        assertThat(ds.importBaseIri).isEqualTo(PREFIX + "/source/lameta-src");
        assertThat(ds.tripleCount).isPositive();
        assertThat(ds.lastImport.statements).isEqualTo(ds.tripleCount);
        assertThat(ds.lastImport.importerVersion).isNotBlank();
        // Test2 holds Test.sprj and empty .meta files: the plugin logs warnings, collected here
        assertThat(ds.lastImport.warnings).isNotEmpty();
        assertThat(subjects(graph)).allSatisfy(s -> assertThat(s.stringValue()).startsWith(PREFIX + "/source/lameta-src/"));
        assertThat(events).hasAtLeastOneElementOfType(ProjectDataChangedEvent.class);

        // Nothing outside the source graph and the metadata graph
        try (RepositoryConnection conn = repo.getConnection()) {
            assertThat(conn.getContextIDs().stream().map(Resource::stringValue).toList())
                    .containsExactlyInAnyOrder(ds.graphIri, RdfContexts.CTX_META,
                            RdfNamespaces.APP + "/projects#" + PROJECT + "/metadata");
        }

        // A re-import gives the same IRIs and the same statements
        Set<Resource> before = subjects(graph);
        ImportResult again = service.synchronizeExternalDataSource("lameta-src", new ImportRequest(null, null, null, null, null));
        assertThat(again.tripleCount).isEqualTo(ds.tripleCount);
        assertThat(again.importer).isEqualTo("lameta");
        assertThat(subjects(graph)).isEqualTo(before);
    }

    @Test
    void aFailedImportKeepsThePreviousContent() throws Exception {
        DataSourceDto ds = createLameta("lameta-src", lametaProject());
        IRI graph = vf.createIRI(ds.graphIri);
        Path notALametaProject = Files.createDirectories(tmp.resolve("empty"));

        assertThatThrownBy(() -> service.synchronizeExternalDataSource("lameta-src",
                new ImportRequest(null, null, null, notALametaProject.toString(), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Import with lameta failed for " + notALametaProject);

        assertThat(size(graph)).isEqualTo(ds.tripleCount);
        // the location of the failed import is not recorded
        assertThat(service.getDataSource("lameta-src").sourceLocation).isEqualTo(lametaProject().toString());
    }

    @Test
    void optionsAndBaseIriAreRecordedAndUsed() throws Exception {
        createLameta("lameta-src", lametaProject());
        ImportResult r = service.synchronizeExternalDataSource("lameta-src", new ImportRequest(null,
                Map.of("unmapped", "drop", "includeContactInfo", "false", "unknown", "x"),
                "http://corpus.example.org/tuwari/", null, null));
        assertThat(r.tripleCount).isPositive();

        DataSourceDto ds = service.getDataSource("lameta-src");
        // defaults and unknown options are not stored; the trailing slash is the plugin's business
        assertThat(ds.importerOptions).containsExactly(Map.entry("unmapped", "drop"));
        assertThat(ds.importBaseIri).isEqualTo("http://corpus.example.org/tuwari/");
        assertThat(subjects(vf.createIRI(ds.graphIri)))
                .allSatisfy(s -> assertThat(s.stringValue()).startsWith("http://corpus.example.org/tuwari/"));
    }

    @Test
    void aDirectoryPluginRefusesAFileAndAnUpload() throws Exception {
        createLameta("lameta-src", lametaProject());
        Path file = Files.writeString(tmp.resolve("a.ttl"), "");
        assertThatThrownBy(() -> service.synchronizeExternalDataSource("lameta-src",
                new ImportRequest(null, null, null, file.toString(), null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reads a directory");
        MockMultipartFile upload = new MockMultipartFile("file", "a.ttl", "text/turtle", new byte[]{1});
        assertThatThrownBy(() -> service.synchronizeExternalDataSource("lameta-src",
                new ImportRequest(null, null, null, null, upload)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reads a path");
    }

    @Test
    void rdfFilesOfAnyFormatGoIntoTheSourceGraph() throws Exception {
        Path rdfXml = Files.writeString(tmp.resolve("data.rdf"), """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <rdf:Description rdf:about="http://ex.org/a"><rdfs:label>A</rdfs:label></rdf:Description>
                </rdf:RDF>""");
        CreateExternalDataSourceRequest req = new CreateExternalDataSourceRequest();
        req.setShortName("files");
        req.setSourceLocation(rdfXml.toString());
        service.createExternalDataSource(req, null);
        DataSourceDto ds = service.getDataSource("files");
        assertThat(ds.importer).isEqualTo(RdfFileImporter.ID);
        assertThat(ds.tripleCount).isEqualTo(1);

        // TriG: the statements' own graphs are replaced by the source's graph
        Path trig = Files.writeString(tmp.resolve("data.trig"),
                "<http://ex.org/g1> { <http://ex.org/b> <http://ex.org/p> 1 . }\n"
                        + "<http://ex.org/g2> { <http://ex.org/c> <http://ex.org/p> 2 . }\n");
        ImportResult r = service.synchronizeExternalDataSource("files", new ImportRequest(null, null, null, trig.toString(), null));
        assertThat(r.tripleCount).isEqualTo(2);
        assertThat(size(vf.createIRI(ds.graphIri))).isEqualTo(2);
        assertThat(service.getDataSource("files").sourceLocation).isEqualTo(trig.toString());

        // Upload of a Turtle file, chosen by its name
        MockMultipartFile upload = new MockMultipartFile("file", "x.ttl", "text/turtle",
                "<http://ex.org/d> <http://ex.org/p> 3 .".getBytes(StandardCharsets.UTF_8));
        assertThat(service.synchronizeExternalDataSource("files", new ImportRequest(null, null, null, null, upload))
                .tripleCount).isEqualTo(1);

        // A malformed file: 400 naming the file and the line, previous content kept
        Path bad = Files.writeString(tmp.resolve("bad.ttl"), "this is not turtle\n");
        assertThatThrownBy(() -> service.synchronizeExternalDataSource("files",
                new ImportRequest(null, null, null, bad.toString(), null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("bad.ttl (line 1)");
        assertThat(size(vf.createIRI(ds.graphIri))).isEqualTo(1);
    }

    @Test
    void storedValuesWithoutPluginAreReportedMissing() throws Exception {
        Path ttl = Files.writeString(tmp.resolve("g.ttl"), "<http://ex.org/a> <http://ex.org/p> 1 .");
        CreateExternalDataSourceRequest req = new CreateExternalDataSourceRequest();
        req.setShortName("gramps");
        req.setSourceLocation(ttl.toString());
        service.createExternalDataSource(req, null);
        IRI ds = vf.createIRI(RdfNamespaces.APP + "/datasource/gramps");
        IRI tool = vf.createIRI(RdfNamespaces.APP, "sourceTool");
        IRI meta = vf.createIRI(RdfContexts.CTX_META);

        // Written by the former form
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.remove(ds, tool, null, meta);
            conn.add(ds, tool, vf.createLiteral("Gramps"), meta);
        }
        DataSourceDto dto = service.getDataSource("gramps");
        assertThat(dto.importerStatus).isEqualTo("MISSING");
        assertThat(dto.importer).isEqualTo("Gramps");
        assertThatThrownBy(() -> service.synchronizeExternalDataSource("gramps", new ImportRequest(null, null, null, null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("No plugin for this data source: \"Gramps\"");

        // Saving the other fields keeps the unknown value; an unknown new value is refused
        UpdateDataSourceRequest keep = new UpdateDataSourceRequest();
        keep.setImporter("Gramps");
        service.updateDataSource("gramps", keep);
        UpdateDataSourceRequest unknown = new UpdateDataSourceRequest();
        unknown.setImporter("tropy");
        assertThatThrownBy(() -> service.updateDataSource("gramps", unknown)).isInstanceOf(BadRequestException.class);

        // "Turn the value to raw RDF"
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.remove(ds, tool, null, meta);
            conn.add(ds, tool, vf.createLiteral("Other"), meta);
        }
        assertThat(service.getDataSource("gramps").importerStatus).isEqualTo("RDF_FILE");
        ImportResult r = service.synchronizeExternalDataSource("gramps", new ImportRequest(null, null, null, null, null));
        assertThat(r.importer).isEqualTo(RdfFileImporter.ID);
        assertThat(service.getDataSource("gramps").sourceTool).isEqualTo(RdfFileImporter.ID);
    }
}
