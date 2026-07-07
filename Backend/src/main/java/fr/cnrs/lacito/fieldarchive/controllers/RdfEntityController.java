package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.dtos.CreateRdfEntityRequest;
import fr.cnrs.lacito.fieldarchive.dtos.RdfEntityDto;
import fr.cnrs.lacito.fieldarchive.dtos.RdfEntitySummaryDto;
import fr.cnrs.lacito.fieldarchive.dtos.UpdateRdfEntityRequest;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/rdf")
public class RdfEntityController {

    private final RdfEntityService service;

    public RdfEntityController(RdfEntityService service) {
        this.service = service;
    }

    // =========================
    // Types RDF disponibles
    // =========================
    @GetMapping("/types")
    public List<String> listTypes() {
        return service.listTypes();
    }

    // =========================
    // Liste des entités par type (vue tableau)
    // =========================
    @GetMapping("/entities")
    public List<RdfEntitySummaryDto> listEntities(@RequestParam("type") String type) {
        return service.listByType(type);
    }

    // =========================
    // Détail d’une entité (panneau de droite)
    // =========================
    @GetMapping("/entity")
    public RdfEntityDto getEntity(@RequestParam("key") String key) {
        return service.getByKey(key);
    }

    // =========================
    // Création d’une entité RDF (interne)
    // =========================
    @PostMapping("/entities")
    public RdfEntityDto createEntity(@RequestBody CreateRdfEntityRequest req) {
        return service.create(req);
    }

    // =========================
    // Mise à jour d’une entité RDF (interne uniquement)
    // =========================
    @PutMapping("/entity")
    public RdfEntityDto updateEntity(@RequestParam("key") String key,
                                     @RequestBody UpdateRdfEntityRequest req) {
        return service.updateByKey(key, req);
    }

    // =========================
    // Suppression d’une entité RDF (interne uniquement)
    // =========================
    @DeleteMapping("/entity")
    public ResponseEntity<?> deleteEntity(@RequestParam("key") String key) {
        service.deleteByKey(key);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}