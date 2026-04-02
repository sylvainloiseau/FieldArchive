package com.uspn.rdf_back.services;

import com.uspn.rdf_back.core.ProjectContext;
import com.uspn.rdf_back.core.RdfContexts;
import com.uspn.rdf_back.core.RdfNamespaces;
import com.uspn.rdf_back.dtos.OntologyClassDto;
import com.uspn.rdf_back.dtos.OntologyLabelDto;
import com.uspn.rdf_back.exception.BadRequestException;
import com.uspn.rdf_back.exception.NotFoundException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class BuiltinOntologyService {
    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final String RICO_LABEL = "RIC-O";
    private static final String RICO_RESOURCE_PATH = "ontologies/rico.rdf";

    private IRI metaCtx() {
        return vf.createIRI(RdfContexts.CTX_META);
    }

    private IRI ricoCtx() {
        return vf.createIRI(RdfContexts.CTX_ONTO_RICO);
    }

    private IRI ontologyType() {
        return vf.createIRI(RdfNamespaces.APP, "OntologyNamespace");
    }

    private IRI pGraph() {
        return vf.createIRI(RdfNamespaces.APP, "graph");
    }

    private IRI pBuiltIn() {
        return vf.createIRI(RdfNamespaces.APP, "builtIn");
    }

    private IRI ricoOntologyIri() {
        return vf.createIRI(RdfNamespaces.RICO);
    }

    private void requireProjectOpen() {
        if (!ProjectContext.isOpen()) {
            throw new BadRequestException("Aucun projet ouvert.");
        }
    }

    public void ensureRicoLoaded() {
        requireProjectOpen();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            IRI ctxMeta = metaCtx();
            IRI ctxRico = ricoCtx();
            IRI rico = ricoOntologyIri();

            boolean graphLoaded = conn.hasStatement(null, null, null, false, ctxRico);
            boolean metadataReady =
                    conn.hasStatement(rico, RDF.TYPE, ontologyType(), false, ctxMeta)
                            && conn.hasStatement(rico, pBuiltIn(), vf.createLiteral(true), false, ctxMeta)
                            && conn.hasStatement(rico, pGraph(), ctxRico, false, ctxMeta);

            if (graphLoaded && metadataReady) {
                return;
            }

            conn.begin();
            try {
                if (!graphLoaded) {
                    loadRicoGraphFromClasspath(conn, ctxRico);
                }

                upsertRicoMetadata(conn, rico, ctxMeta, ctxRico);
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public List<OntologyLabelDto> getKnownOntologies() {
        requireProjectOpen();

        String query = """
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX app: <%s>
                SELECT ?url ?label ?graph
                WHERE {
                  GRAPH <%s> {
                    ?url rdf:type app:OntologyNamespace ;
                         rdfs:label ?label ;
                         app:graph ?graph ;
                         app:builtIn true .
                  }
                }
                ORDER BY LCASE(STR(?label))
                """.formatted(RdfNamespaces.APP, RdfContexts.CTX_META);

        List<OntologyLabelDto> out = new ArrayList<>();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {
                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    out.add(new OntologyLabelDto(
                            binding.getValue("url").stringValue(),
                            binding.getValue("label").stringValue(),
                            binding.getValue("graph").stringValue(),
                            true
                    ));
                }
            }
        }

        return out;
    }

    public List<OntologyClassDto> getKnownOntologyClasses(String ontologyUrl) {
        requireProjectOpen();

        if (ontologyUrl == null || ontologyUrl.isBlank()) {
            throw new BadRequestException("L'URL de l'ontologie est obligatoire.");
        }

        final IRI ontology;
        try {
            ontology = vf.createIRI(ontologyUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("URL d'ontologie invalide : " + ontologyUrl);
        }

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            IRI ctxMeta = metaCtx();

            boolean exists =
                    conn.hasStatement(ontology, RDF.TYPE, ontologyType(), false, ctxMeta)
                            && conn.hasStatement(ontology, pBuiltIn(), vf.createLiteral(true), false, ctxMeta);

            if (!exists) {
                throw new NotFoundException("Ontologie connue introuvable : " + ontology.stringValue());
            }

            String graphIri = getIri(conn, ontology, pGraph(), ctxMeta);
            if (graphIri == null) {
                throw new NotFoundException("Aucun graphe associé à l'ontologie : " + ontology.stringValue());
            }

            String query = """
                    PREFIX owl: <http://www.w3.org/2002/07/owl#>
                    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                    SELECT ?type (SAMPLE(?lbl) AS ?label)
                    WHERE {
                      GRAPH <%s> {
                        { ?type a owl:Class . }
                        UNION
                        { ?type a rdfs:Class . }

                        FILTER(isIRI(?type))
                        FILTER(STRSTARTS(STR(?type), "%s"))

                        OPTIONAL {
                          ?type rdfs:label ?lbl .
                          FILTER(lang(?lbl) = "" || langMatches(lang(?lbl), "en") || langMatches(lang(?lbl), "fr"))
                        }
                      }
                    }
                    GROUP BY ?type
                    ORDER BY ?type
                    """.formatted(graphIri, ontology.stringValue());

            List<OntologyClassDto> out = new ArrayList<>();
            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {
                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    Value typeValue = binding.getValue("type");
                    if (!(typeValue instanceof IRI typeIri)) {
                        continue;
                    }

                    String localName = typeIri.getLocalName();
                    Value labelValue = binding.getValue("label");
                    String label = (labelValue != null) ? labelValue.stringValue() : localName;

                    out.add(new OntologyClassDto(
                            typeIri.stringValue(),
                            localName,
                            label
                    ));
                }
            }

            return out;
        }
    }

    private void loadRicoGraphFromClasspath(RepositoryConnection conn, IRI ctxRico) {
        ClassPathResource resource = new ClassPathResource(RICO_RESOURCE_PATH);
        if (!resource.exists()) {
            throw new RuntimeException("Fichier ontologique introuvable dans resources : " + RICO_RESOURCE_PATH);
        }

        conn.clear(ctxRico);

        try (InputStream inputStream = resource.getInputStream()) {
            conn.add(inputStream, "https://www.ica.org/standards/RiC/ontology", RDFFormat.RDFXML, ctxRico);
        } catch (IOException e) {
            throw new RuntimeException("Impossible de lire le fichier RiC-O : " + RICO_RESOURCE_PATH, e);
        }
    }

    private void upsertRicoMetadata(RepositoryConnection conn, IRI rico, IRI ctxMeta, IRI ctxRico) {
        conn.remove(rico, RDF.TYPE, null, ctxMeta);
        conn.remove(rico, RDFS.LABEL, null, ctxMeta);
        conn.remove(rico, pGraph(), null, ctxMeta);
        conn.remove(rico, pBuiltIn(), null, ctxMeta);

        conn.add(rico, RDF.TYPE, ontologyType(), ctxMeta);
        conn.add(rico, RDFS.LABEL, vf.createLiteral(RICO_LABEL), ctxMeta);
        conn.add(rico, pGraph(), ctxRico, ctxMeta);
        conn.add(rico, pBuiltIn(), vf.createLiteral(true), ctxMeta);
    }

    private String getIri(RepositoryConnection conn, IRI subject, IRI predicate, IRI ctx) {
        try (RepositoryResult<Statement> stmts = conn.getStatements(subject, predicate, null, false, ctx)) {
            if (stmts.hasNext()) {
                Value value = stmts.next().getObject();
                if (value.isIRI()) {
                    return value.stringValue();
                }
            }
        }
        return null;
    }

}
