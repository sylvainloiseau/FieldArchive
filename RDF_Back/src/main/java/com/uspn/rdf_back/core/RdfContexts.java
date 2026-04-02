package com.uspn.rdf_back.core;
//un contexte = un graphe nommé
public final class RdfContexts {
    private RdfContexts() {}

    // Contexte où on stocke les METADONNEES des sources (en RDF)
    // graphe nommé pour les métadonnées (projet, sources de données, etc.)
    public static final String CTX_META = RdfNamespaces.APP + "context/metadata";

    // Contexte éditable interne (source interne)
    public static final String CTX_INTERNAL = RdfNamespaces.APP + "context/internal";

    public static final String CTX_ONTO_RICO = RdfNamespaces.APP + "context/ontology/rico";
}
