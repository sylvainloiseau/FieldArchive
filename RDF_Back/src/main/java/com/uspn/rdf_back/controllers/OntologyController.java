package com.uspn.rdf_back.controllers;

import com.uspn.rdf_back.dtos.OntologyLabelDto;
import com.uspn.rdf_back.dtos.SaveOntologyLabelRequest;
import com.uspn.rdf_back.services.OntologyService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import com.uspn.rdf_back.dtos.OntologyClassDto;
import com.uspn.rdf_back.services.BuiltinOntologyService;

@RestController
@RequestMapping("/ontology")
public class OntologyController {

    private final OntologyService ontologyService;
    private final BuiltinOntologyService builtinOntologyService;

    public OntologyController(OntologyService ontologyService,
                              BuiltinOntologyService builtinOntologyService) {
        this.ontologyService = ontologyService;
        this.builtinOntologyService = builtinOntologyService;
    }

    // =============================
    // TYPES RDF
    // =============================
    @GetMapping("/types")
    public List<String> getTypes() {
        return ontologyService.getAllTypes();
    }

    // =============================
    // PROPRIETES D'UN TYPE
    // =============================
    @GetMapping("/properties")
    public List<String> getProperties(@RequestParam String type) {
        return ontologyService.getPropertiesOfType(type);
    }

    // =============================
    // RESSOURCES D'UN TYPE
    // =============================
    @GetMapping("/resources")
    public List<String> getResources(@RequestParam String type) {
        return ontologyService.getResourcesOfType(type);
    }


    // =============================
    // LISTER LES ONTOLOGIES METADONNEES
    // =============================
    @GetMapping("/labels")
    public List<OntologyLabelDto> getOntologyLabels() {
        return ontologyService.getOntologyLabels();
    }

    // =============================
    // AJOUTER UNE ONTOLOGIE
    // =============================
    @PostMapping("/labels")
    public void addOntologyLabel(@RequestBody SaveOntologyLabelRequest request) {
        ontologyService.addOntologyLabel(request);
    }

    // =============================
    // MODIFIER LE LABEL D'UNE ONTOLOGIE
    // =============================
    @PutMapping("/labels")
    public void updateOntologyLabel(@RequestBody SaveOntologyLabelRequest request) {
        ontologyService.updateOntologyLabel(request);
    }

    // =============================
    // SUPPRIMER UNE ONTOLOGIE
    // =============================
    @DeleteMapping("/labels")
    public void deleteOntologyLabel(@RequestParam String url) {
        ontologyService.deleteOntologyLabel(url);
    }

    // =============================
    // CHARGER MANUELLEMENT RIC-O
    // =============================
    @PostMapping("/known/rico/load")
    public void loadRico() {
        builtinOntologyService.ensureRicoLoaded();
    }

    // =============================
    // ONTOLOGIES CONNUES PAR L'APPLICATION
    // =============================
    @GetMapping("/known")
    public List<OntologyLabelDto> getKnownOntologies() {
        return builtinOntologyService.getKnownOntologies();
    }

    // =============================
    // CLASSES D'UNE ONTOLOGIE CONNUE
    // =============================
    @GetMapping("/known/classes")
    public List<OntologyClassDto> getKnownOntologyClasses(@RequestParam String url) {
        return builtinOntologyService.getKnownOntologyClasses(url);
    }
}