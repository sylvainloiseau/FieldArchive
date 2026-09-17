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
    // List of entities by type
    // =========================
    @GetMapping("/entities")
    public List<RdfEntitySummaryDto> listEntities(
            @RequestParam(value = "type", required = false) List<String> types,
            @RequestParam(value = "includeSubtypes", required = false, defaultValue = "false") boolean includeSubtypes) {

        if (types == null || types.isEmpty()) {
            return service.listWithoutType();
        }

        return service.listByTypes(types, includeSubtypes);
    }

    // =========================
    // Detail on an entity
    // =========================
    @GetMapping("/entity")
    public RdfEntityDto getEntity(@RequestParam("key") String key) {
        return service.getByKey(key);
    }

    // =========================
    // Entity creation (always in the InternalDataSource of a project)
    // =========================
    @PostMapping("/entities")
    public RdfEntityDto createEntity(@RequestBody CreateRdfEntityRequest req) {
        return service.create(req);
    }

    // =========================
    // Update of an entity (always in the InternalDataSource of a project)
    // =========================
    @PutMapping("/entity")
    public RdfEntityDto updateEntity(@RequestParam("key") String key,
                                     @RequestBody UpdateRdfEntityRequest req) {
        return service.updateByKey(key, req);
    }

    // =========================
    // Deleting an entity (always in the InternalDataSource of a project)
    // =========================
    @DeleteMapping("/entity")
    public ResponseEntity<?> deleteEntity(@RequestParam("key") String key) {
        service.deleteByKey(key);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
