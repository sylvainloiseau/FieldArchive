xml sel -I -N rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" -N rico="https://www.ica.org/standards/RiC/ontology#" -N owl="http://www.w3.org/2002/07/owl#" -t -m "//owl:ObjectProperty" -v 'concat(@rdf:about, " ; ", owl:inverseOf/@rdf:resource, " ; ", rico:RiCCMCorrespondingComponent, "
")' Backend/src/main/resources/ontologies/rico.rdf  > Doc/inverse.txt
