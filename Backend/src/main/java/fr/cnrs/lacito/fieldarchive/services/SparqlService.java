package fr.cnrs.lacito.fieldarchive.services;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SparqlService {
    public void update(String sparql) {

        requireProjectOpen();

        String q = sparql.trim().toLowerCase();

        // bloquer SELECT
        if (q.contains("select")) {
            throw new BadRequestException("Les requêtes SELECT ne sont pas autorisées dans cet endpoint.");
        }

        // autoriser uniquement les requêtes UPDATE
        if (!(q.contains("insert") ||
                q.contains("delete") ||
                q.contains("load") ||
                q.contains("clear") ||
                q.contains("create") ||
                q.contains("drop") ||
                q.contains("with"))) {

            throw new BadRequestException("La requête doit être une requête SPARQL UPDATE.");
        }

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {

            Update updateQuery = conn.prepareUpdate(QueryLanguage.SPARQL, sparql);

            updateQuery.execute();

        } catch (Exception e) {

            throw new BadRequestException(
                    "Erreur lors de l'exécution de la requête UPDATE : " + e.getMessage()
            );
        }
    }



    private void requireProjectOpen() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("Aucun projet ouvert.");
    }

    public List<Map<String, String>> select(String sparql) {
        requireProjectOpen();

        String q = sparql.trim().toLowerCase();
        if (q.contains("insert") || q.contains("delete") || q.contains("update") || q.contains("load") || q.contains("clear") || q.contains("create") || q.contains("drop")) {
            throw new BadRequestException("Uniquement SELECT pour ce endpoint.");
        }
        if (!q.contains("select")) {
            throw new BadRequestException("Uniquement SELECT pour ce endpoint.");
        }

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            var query = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql);
            try (TupleQueryResult res = query.evaluate()) {
                List<String> vars = res.getBindingNames();
                List<Map<String, String>> out = new ArrayList<>();
                while (res.hasNext()) {
                    var b = res.next();
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String v : vars) {
                        row.put(v, b.hasBinding(v) ? b.getValue(v).stringValue() : null);
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }
}