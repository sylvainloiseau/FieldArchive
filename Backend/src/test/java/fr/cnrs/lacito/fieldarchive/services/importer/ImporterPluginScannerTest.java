package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.fieldarchive.services.importer.ImporterPluginScanner.PluginDirectory;
import fr.cnrs.lacito.fieldarchive.services.importer.ImporterPluginScanner.ScanResult;
import fr.cnrs.lacito.fieldarchive.services.importer.testplugins.PluginHelper;
import fr.cnrs.lacito.fieldarchive.services.importer.testplugins.SampleProviders;
import fr.cnrs.lacito.fieldarchive.services.importer.testplugins.SampleProviders.*;
import fr.cnrs.lacito.ricimporterplugin.InputKind;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPluginScannerTest {

    @TempDir
    Path tmp;

    private final ImporterPluginScanner scanner = new ImporterPluginScanner(PluginJars.parentHidingTestPlugins());
    private ScanResult result;

    @AfterEach
    void closeLoaders() {
        if (result != null) result.loaders().forEach(ImporterPluginScanner::closeQuietly);
    }

    private static Class<?>[] classes(Class<?>... c) {
        return c;
    }

    /** SampleProviders and the given provider classes (subclasses need their parents). */
    private static Class<?>[] withBase(Class<?>... c) {
        Class<?>[] out = new Class<?>[c.length + 2];
        out[0] = SampleProviders.class;
        out[1] = Alpha.class;
        System.arraycopy(c, 0, out, 2, c.length);
        return out;
    }

    private ScanResult scan(Path bundled, Path user) {
        result = scanner.scan(List.of(new PluginDirectory(bundled, ImporterOrigin.BUNDLED),
                new PluginDirectory(user, ImporterOrigin.USER)));
        return result;
    }

    @Test
    void loadsAPluginFromItsJar() throws IOException {
        Path bundled = tmp.resolve("bundled");
        PluginJars.jar(bundled.resolve("alpha.jar"), withBase(), Alpha.class);

        ScanResult r = scan(bundled, tmp.resolve("none"));

        assertThat(r.problems()).isEmpty();
        assertThat(r.plugins()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("alpha");
            assertThat(p.inputKind()).isEqualTo(InputKind.DIRECTORY);
            assertThat(p.origin()).isEqualTo(ImporterOrigin.BUNDLED);
            assertThat(p.options()).extracting(o -> o.name()).containsExactly("label");
            assertThat(p.loader()).isInstanceOf(URLClassLoader.class);
        });

        Model model = new LinkedHashModel();
        Path input = Files.createDirectories(tmp.resolve("input"));
        r.plugins().get(0).emit(ImportInput.ofPath(input), new ImportSettings("http://ex.org/b", Map.of("label", "x")),
                new StatementCollector(model));
        assertThat(model).hasSize(1);
        assertThat(model.subjects().iterator().next().stringValue()).isEqualTo("http://ex.org/b/item");
    }

    @Test
    void theUserDirectoryWinsOverTheBundledOne() throws IOException {
        Path bundled = tmp.resolve("bundled");
        Path user = tmp.resolve("user");
        PluginJars.jar(bundled.resolve("alpha.jar"), withBase(), Alpha.class);
        PluginJars.jar(user.resolve("alpha-user.jar"), withBase(UserAlpha.class), UserAlpha.class);

        ScanResult r = scan(bundled, user);

        assertThat(r.plugins()).singleElement().satisfies(p -> {
            assertThat(p.description()).isEqualTo("Alpha (user)");
            assertThat(p.origin()).isEqualTo(ImporterOrigin.USER);
        });
        assertThat(r.problems()).singleElement().satisfies(p -> {
            assertThat(p.error()).isFalse();
            assertThat(p.location()).endsWith("alpha.jar");
        });
    }

    @Test
    void sameNameTwiceInOneDirectoryRefusesBoth() throws IOException {
        Path bundled = tmp.resolve("bundled");
        PluginJars.jar(bundled.resolve("a.jar"), withBase(), Alpha.class);
        PluginJars.jar(bundled.resolve("b.jar"), withBase(AlphaTwin.class), AlphaTwin.class);

        ScanResult r = scan(bundled, null);

        assertThat(r.plugins()).isEmpty();
        assertThat(r.problems()).hasSize(2).allSatisfy(p -> assertThat(p.error()).isTrue());
    }

    @Test
    void reportsWithoutStoppingTheScan() throws IOException {
        Path bundled = tmp.resolve("bundled");
        PluginJars.jar(bundled.resolve("1-reserved.jar"), withBase(Reserved.class), Reserved.class);
        PluginJars.jar(bundled.resolve("2-broken.jar"), withBase(Broken.class), Broken.class);
        PluginJars.jar(bundled.resolve("3-empty.jar"), withBase());
        Files.writeString(bundled.resolve("4-corrupt.jar"), "not a jar");
        PluginJars.jar(bundled.resolve("5-alpha.jar"), withBase(), Alpha.class);
        Files.writeString(bundled.resolve("readme.txt"), "ignored");

        ScanResult r = scan(bundled, null);

        assertThat(r.plugins()).extracting(PluginImporter::id).containsExactly("alpha");
        assertThat(r.problems()).extracting(p -> Path.of(p.location()).getFileName().toString())
                .containsExactlyInAnyOrder("1-reserved.jar", "2-broken.jar", "3-empty.jar", "4-corrupt.jar");
        assertThat(r.problems()).allSatisfy(p -> assertThat(p.error()).isTrue());
        assertThat(r.problems()).filteredOn(p -> p.location().endsWith("4-corrupt.jar"))
                .singleElement().satisfies(p -> assertThat(p.message()).startsWith("Not a valid jar file"));
    }

    @Test
    void aSubdirectoryIsOnePluginMadeOfAllItsJars() throws IOException {
        Path user = tmp.resolve("user");
        PluginJars.jar(user.resolve("helper/main.jar"), withBase(HelperBacked.class), HelperBacked.class);
        PluginJars.jar(user.resolve("helper/lib.jar"), classes(PluginHelper.class));

        ScanResult r = scan(null, user);

        assertThat(r.problems()).isEmpty();
        assertThat(r.plugins()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("helper");
            assertThat(p.description()).isEqualTo("From the helper jar");
            assertThat(p.location().getFileName().toString()).isEqualTo("helper");
        });
    }
}
