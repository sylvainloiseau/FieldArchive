package fr.cnrs.lacito.fieldarchive.controllers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
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

    /**
     * Creates an external source and imports it: the uploaded RDF file, else
     * {@code dataSource.sourceLocation} (with a plugin, the path it reads). The source is kept,
     * empty, when that first import fails.
     */
    @PostMapping(value = "/external", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createExternal(
            @RequestPart("dataSource") CreateExternalDataSourceRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file) {

        ImportResult result = dataSourceService.createExternalDataSource(request, file);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "created");
        if (result != null) body.put("import", result);
        return ResponseEntity.ok(body);
    }

    /**
     * Re-imports an external source, replacing its graph. Every parameter is optional and
     * defaults to what is recorded for the source; the values given are recorded once the import
     * succeeds. @RequestParam rather than @RequestPart, so that a body-less POST works as well as
     * a multipart upload (same style as ProjectController.importBackup).
     *
     * @param importer        a plugin name or "rdf-file"
     * @param importerOptions the plugin options, as a JSON object
     * @param importBaseIri   the prefix of the IRIs a plugin mints
     */
    @PostMapping("/{shortName}/sync")
    public ImportResult syncExternal(
            @PathVariable String shortName,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sourceLocation", required = false) String sourceLocation,
            @RequestParam(value = "importer", required = false) String importer,
            @RequestParam(value = "importerOptions", required = false) String importerOptions,
            @RequestParam(value = "importBaseIri", required = false) String importBaseIri) {

        return dataSourceService.synchronizeExternalDataSource(shortName, new DataSourceService.ImportRequest(
                importer, parseOptions(importerOptions), importBaseIri, sourceLocation, file));
    }

    private static Map<String, String> parseOptions(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new BadRequestException("importerOptions is not a JSON object of strings: " + e.getMessage());
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

}
