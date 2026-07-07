package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.CreateInternalDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.dtos.CreateExternalDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.dtos.DataSourceDto;
import fr.cnrs.lacito.fieldarchive.dtos.UpdateDataSourceRequest;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    // CREATE internal
    @PostMapping("/internal")
    public ResponseEntity<?> createInternal(@RequestBody CreateInternalDataSourceRequest request) {
        dataSourceService.createInternalDataSource(request);
        return ResponseEntity.ok(Map.of("status", "created"));
    }

    // READ ALL
    @GetMapping
    public List<DataSourceDto> list() {
        return dataSourceService.listDataSources();
    }

    // READ ONE
    @GetMapping("/{shortName}")
    public DataSourceDto get(@PathVariable String shortName) {
        return dataSourceService.getDataSource(shortName);
    }

    // UPDATE
    @PutMapping("/{shortName}")
    public ResponseEntity<?> update(@PathVariable String shortName,
                                    @RequestBody UpdateDataSourceRequest req) {
        dataSourceService.updateDataSource(shortName, req);
        return ResponseEntity.ok(Map.of("status", "updated"));
    }

    // DELETE
    @DeleteMapping("/{shortName}")
    public ResponseEntity<?> delete(@PathVariable String shortName) {
        dataSourceService.deleteDataSource(shortName);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/external")
    public ResponseEntity<?> createExternal(
            @RequestBody CreateExternalDataSourceRequest request) {

        dataSourceService.createExternalDataSource(request);
        return ResponseEntity.ok(Map.of("status", "created"));
    }

    @PostMapping("/{shortName}/sync")
    public ResponseEntity<?> syncExternal(@PathVariable String shortName) {

        dataSourceService.synchronizeExternalDataSource(shortName);
        return ResponseEntity.ok(Map.of("status", "synchronized"));
    }

}
