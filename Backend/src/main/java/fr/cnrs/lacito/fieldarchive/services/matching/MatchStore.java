package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.DataSourcePair;
import fr.cnrs.lacito.fieldarchive.services.matching.cluster.EntityNode;
import fr.cnrs.lacito.fieldarchive.utils.ProjectJsonFile;
import fr.cnrs.lacito.fieldarchive.utils.ProjectsDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

/** Reads and writes {@code data_matching.json}; link helpers shared by the services. */
@Component
public class MatchStore {

    public static final String FILE_NAME = "data_matching.json";

    private final ProjectJsonFile<MatchingFile> file;

    public MatchStore(ProjectsDirectory projectsDirectory) {
        this.file = new ProjectJsonFile<>(projectsDirectory, FILE_NAME, MatchingFile.class, MatchingFile::new);
    }

    public MatchingFile read(String projectName) {
        MatchingFile f = file.read(projectName);
        if (f.links == null) f.links = new ArrayList<>();
        if (f.runs == null) f.runs = new java.util.LinkedHashMap<>();
        f.links.removeIf(l -> l == null || l.a == null || l.b == null || l.status == null);
        return f;
    }

    public void write(String projectName, MatchingFile f) {
        file.write(projectName, f);
    }

    public String lastWarning() {
        return file.lastWarning();
    }

    public byte[] rawContent(String projectName) {
        return file.rawContent(projectName);
    }

    public void restore(String projectName, byte[] content) throws IOException {
        file.restore(projectName, content);
    }

    // =========================
    //  Link helpers
    // =========================

    /** Key of an unordered pair of nodes (the canonical order is applied). */
    public static String nodePairKey(EntityNode x, EntityNode y) {
        boolean swap = x.dataSource().compareTo(y.dataSource()) > 0
                || x.dataSource().equals(y.dataSource()) && x.iri().compareTo(y.iri()) > 0;
        EntityNode a = swap ? y : x, b = swap ? x : y;
        return a.dataSource() + "\u0000" + a.iri() + "\u0000" + b.dataSource() + "\u0000" + b.iri();
    }

    public static String nodePairKey(MatchLink l) {
        return nodePairKey(EntityNode.of(l.a), EntityNode.of(l.b));
    }

    public static DataSourcePair pairOf(MatchLink l) {
        return DataSourcePair.of(l.a.dataSource, l.b.dataSource);
    }

    /** Puts {@code a} and {@code b} in the canonical order of their pair. */
    public static void canonicalize(MatchLink l) {
        if (l.a.dataSource.compareTo(l.b.dataSource) > 0) {
            EntityNodeDto swap = l.a;
            l.a = l.b;
            l.b = swap;
        }
    }

    public static Optional<MatchLink> find(MatchingFile f, String linkId) {
        return f.links.stream().filter(l -> l.id.equals(linkId)).findFirst();
    }

    public static Optional<MatchLink> findByNodes(MatchingFile f, EntityNode x, EntityNode y) {
        String key = nodePairKey(x, y);
        return f.links.stream().filter(l -> nodePairKey(l).equals(key)).findFirst();
    }
}
