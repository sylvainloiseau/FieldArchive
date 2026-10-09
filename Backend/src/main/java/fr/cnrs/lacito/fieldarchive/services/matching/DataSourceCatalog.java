package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.NotFoundException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * The data sources of the open project as the matching needs them (short name, graph, last
 * change), read directly from the metadata graph.
 */
@Component
public class DataSourceCatalog {

    /** @param lastChange latest of {@code app:lastSync} and {@code dcterms:modified}, or null */
    public record DataSourceInfo(String shortName, String longName, String type, IRI graph, Instant lastChange) {}

    public List<DataSourceInfo> list() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("No project is open.");
        String q = """
                PREFIX app: <%s>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                SELECT ?sn ?type ?g ?label ?sync ?mod WHERE {
                  GRAPH <%s> {
                    ?ds app:sourceType ?type ; app:shortName ?sn ; app:graph ?g .
                    OPTIONAL { ?ds rdfs:label ?label }
                    OPTIONAL { ?ds app:lastSync ?sync }
                    OPTIONAL { ?ds dcterms:modified ?mod }
                  }
                }""".formatted(RdfNamespaces.APP, RdfContexts.CTX_META);
        Map<String, DataSourceInfo> byName = new TreeMap<>();
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection();
             TupleQueryResult r = conn.prepareTupleQuery(q).evaluate()) {
            for (BindingSet bs : r) {
                if (!(bs.getValue("g") instanceof IRI graph)) continue;
                String sn = bs.getValue("sn").stringValue();
                Instant change = latest(instant(bs.getValue("sync")), instant(bs.getValue("mod")));
                DataSourceInfo previous = byName.get(sn);
                if (previous != null) change = latest(change, previous.lastChange());
                Value label = bs.getValue("label");
                byName.put(sn, new DataSourceInfo(sn, label != null ? label.stringValue() : sn,
                        bs.getValue("type").stringValue(), graph, change));
            }
        }
        return new ArrayList<>(byName.values());
    }

    public DataSourceInfo get(String shortName) {
        return find(shortName).orElseThrow(() -> new NotFoundException("Unknown data source: " + shortName));
    }

    public Optional<DataSourceInfo> find(String shortName) {
        return list().stream().filter(d -> d.shortName().equals(shortName)).findFirst();
    }

    public static IRI graphIri(String iri) {
        return SimpleValueFactory.getInstance().createIRI(iri);
    }

    static Instant instant(Value v) {
        if (v == null) return null;
        String s = v.stringValue();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    static Instant latest(Instant x, Instant y) {
        if (x == null) return y;
        if (y == null) return x;
        return x.isAfter(y) ? x : y;
    }
}
