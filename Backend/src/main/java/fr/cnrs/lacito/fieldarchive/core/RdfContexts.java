package fr.cnrs.lacito.fieldarchive.core;
//un contexte = un graphe nommé
public final class RdfContexts {
    private RdfContexts() {}

    // Contexte où on stocke les METADONNEES des sources (en RDF)
    // graphe nommé pour les métadonnées (projet, sources de données, etc.)
    public static final String CTX_META = RdfNamespaces.APP + "/context/metadata";
    public static final String CTX_ONTO_RICO = RdfNamespaces.APP + "/context/ontology/rico";
}
