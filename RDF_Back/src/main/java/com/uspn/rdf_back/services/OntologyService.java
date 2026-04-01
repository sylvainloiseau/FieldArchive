package com.uspn.rdf_back.services;

import com.uspn.rdf_back.core.ProjectContext;
import com.uspn.rdf_back.core.RdfContexts;
import com.uspn.rdf_back.core.RdfNamespaces;
import com.uspn.rdf_back.dtos.OntologyLabelDto;
import com.uspn.rdf_back.dtos.SaveOntologyLabelRequest;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class OntologyService {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private IRI metaCtx() {
        return vf.createIRI(RdfContexts.CTX_META);
    }

    private IRI ontologyType() {
        return vf.createIRI(RdfNamespaces.APP, "OntologyNamespace");
    }

    private void requireProjectOpen() {
        if (!ProjectContext.isOpen()) {
            throw new BadRequestException("Aucun projet ouvert.");
        }
    }

    private IRI toOntologyIri(String url) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("L'URL de l'ontologie est obligatoire.");
        }

        try {
            return vf.createIRI(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("URL invalide : " + url);
        }
    }

    private String cleanLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new BadRequestException("Le label est obligatoire.");
        }
        return label.trim();
    }

    private String getLabel(RepositoryConnection conn, IRI ontology, IRI ctxMeta) {
        try (RepositoryResult<Statement> stmts =
                     conn.getStatements(ontology, RDFS.LABEL, null, false, ctxMeta)) {
            if (stmts.hasNext()) {
                Value v = stmts.next().getObject();
                return v.stringValue();
            }
        }
        return ontology.stringValue();
    }

    // =============================
    // TYPES RDF
    // =============================
    public List<String> getAllTypes() {

        String query = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

        SELECT DISTINCT ?type WHERE {
            ?s rdf:type ?type .
        }
        """;

        List<String> types = new ArrayList<>();

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {

                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    Value type = binding.getValue("type");
                    types.add(type.stringValue());
                }
            }
        }

        return types;
    }

    // =============================
    // PROPRIETES D'UN TYPE
    // =============================
    public List<String> getPropertiesOfType(String type) {

        String query =
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                        "SELECT DISTINCT ?p WHERE { " +
                        " ?s rdf:type <" + type + "> . " +
                        " ?s ?p ?o . " +
                        "}";

        List<String> properties = new ArrayList<>();

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {

                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    properties.add(binding.getValue("p").stringValue());
                }
            }
        }

        return properties;
    }

    // =============================
    // RESSOURCES D'UN TYPE
    // =============================
    public List<String> getResourcesOfType(String type) {

        String query =
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                        "SELECT DISTINCT ?s WHERE { " +
                        " ?s rdf:type <" + type + "> . " +
                        "}";

        List<String> resources = new ArrayList<>();

        try (RepositoryConnection conn =
                     ProjectContext.getRepository().getConnection()) {

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);

            try (TupleQueryResult result = tupleQuery.evaluate()) {

                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    resources.add(binding.getValue("s").stringValue());
                }
            }
        }

        return resources;
    }

    // =========================================================
    // NOUVEAU : LISTER LES ONTOLOGIES STOCKEES EN METADONNEES
    // =========================================================
    public List<OntologyLabelDto> getOntologyLabels() {
        requireProjectOpen();

        List<OntologyLabelDto> out = new ArrayList<>();
        IRI ctxMeta = metaCtx();
        IRI type = ontologyType();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            try (RepositoryResult<Statement> stmts =
                         conn.getStatements(null, RDF.TYPE, type, false, ctxMeta)) {

                while (stmts.hasNext()) {
                    Statement st = stmts.next();

                    if (st.getSubject() instanceof IRI ontology) {
                        out.add(new OntologyLabelDto(
                                ontology.stringValue(),
                                getLabel(conn, ontology, ctxMeta)
                        ));
                    }
                }
            }
        }

        return out;
    }

    // =========================================================
    // NOUVEAU : AJOUTER UNE ONTOLOGIE DANS CTX_META
    // =========================================================
    public void addOntologyLabel(SaveOntologyLabelRequest request) {
        requireProjectOpen();

        if (request == null) {
            throw new BadRequestException("Requête invalide.");
        }

        IRI ontology = toOntologyIri(request.getUrl());
        String label = cleanLabel(request.getLabel());
        IRI ctxMeta = metaCtx();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (conn.hasStatement(ontology, RDF.TYPE, ontologyType(), false, ctxMeta)) {
                throw new BadRequestException("Cette ontologie existe déjà : " + ontology.stringValue());
            }

            conn.begin();
            conn.add(ontology, RDF.TYPE, ontologyType(), ctxMeta);
            conn.add(ontology, RDFS.LABEL, vf.createLiteral(label), ctxMeta);
            conn.commit();
        }
    }

    // =========================================================
    // NOUVEAU : MODIFIER LE LABEL D'UNE ONTOLOGIE
    // =========================================================
    public void updateOntologyLabel(SaveOntologyLabelRequest request) {
        requireProjectOpen();

        if (request == null) {
            throw new BadRequestException("Requête invalide.");
        }

        IRI ontology = toOntologyIri(request.getUrl());
        String label = cleanLabel(request.getLabel());
        IRI ctxMeta = metaCtx();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ontology, RDF.TYPE, ontologyType(), false, ctxMeta)) {
                throw new NotFoundException("Ontologie introuvable : " + ontology.stringValue());
            }

            conn.begin();
            conn.remove(ontology, RDFS.LABEL, null, ctxMeta);
            conn.add(ontology, RDFS.LABEL, vf.createLiteral(label), ctxMeta);
            conn.commit();
        }
    }

    // =========================================================
    // NOUVEAU : SUPPRIMER UNE ONTOLOGIE
    // =========================================================
    public void deleteOntologyLabel(String url) {
        requireProjectOpen();

        IRI ontology = toOntologyIri(url);
        IRI ctxMeta = metaCtx();

        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            if (!conn.hasStatement(ontology, RDF.TYPE, ontologyType(), false, ctxMeta)) {
                throw new NotFoundException("Ontologie introuvable : " + ontology.stringValue());
            }

            conn.begin();
            conn.remove(ontology, null, null, ctxMeta);
            conn.commit();
        }
    }

}