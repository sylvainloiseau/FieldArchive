package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.exceptions.ImportException;
import fr.cnrs.lacito.fieldarchive.services.FileExportService;
import fr.cnrs.lacito.fieldarchive.services.FileImportService;
import fr.cnrs.lacito.fieldarchive.services.ProjectService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Map;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final FileImportService fileImportService;
    private final FileExportService fileExportService;

    public ProjectController(ProjectService projectService, FileImportService fileImportService, FileExportService fileExportService) {
        this.projectService = projectService;
        this.fileImportService = fileImportService;
        this.fileExportService = fileExportService;
    }

    @PostMapping(value = "/import-backup", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importBackup(@RequestParam("file") MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            ImportResult result = fileImportService.importBackup(is);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "ok");
            body.put("message", "Project is successfully restored !");
            body.put("project", result.message);
            return ResponseEntity.ok(body);

        } catch (ImportException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Error while reading file."));
        }
    }

    @GetMapping(value = "/export/backup", produces = "application/zip")
    public void exportBackup(HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"project-backup.zip\"");
        fileExportService.exportBackup(response.getOutputStream());
    }

    @GetMapping(value = "/export/internal", produces = "text/turtle")
    public void exportInternal(HttpServletResponse response) throws IOException {
        response.setContentType("text/turtle");
        response.setHeader("Content-Disposition", "attachment; filename=\"internal-data.ttl\"");
        fileExportService.exportInternalDataSource(response.getOutputStream());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importExternalTurtle(
            @RequestParam("file") MultipartFile file) {

        try (InputStream is = file.getInputStream()) {
            String baseURI = projectService.readCurrentProject().prefix;
            ImportResult result = fileImportService.importTurtle(is, baseURI);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "ok");
            body.put("message", "Internal DataSource was successfully updated !");
            body.put("project", result.message);
            return ResponseEntity.ok(body);
        } catch (ImportException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/open")
    public Map<String, Object> open(@RequestBody CreateProjectRequest req) {
        ProjectDto dto = projectService.openProject(req.getName(), req.isPersistent(), req.getDescription(), req.getPrefix());
        return Map.of(
                "status", "ok",
                "project", dto.name
        );
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody CreateProjectRequest req) {
        ProjectDto dto = projectService.createProject(req.getName(), req.isPersistent(), req.getDescription(), req.getPrefix());
        return Map.of(
                "status", "ok",
                "project", dto.name
        );
    }

    @GetMapping("/current")
    public ProjectDto current() {
        return projectService.readCurrentProject();
    }

    @DeleteMapping("/{projectName}")
    public ResponseEntity<String> deleteProject(@PathVariable String projectName) {
        try {
            projectService.deleteProject(projectName);
            return ResponseEntity.ok("Project deleted successfully");

        } catch (IllegalArgumentException e) {
            return ResponseEntity
                    .badRequest()
                    .body(e.getMessage());

        } catch (FileNotFoundException e) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());

        } catch (IOException e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error deleting project");
        }
    }

    @PostMapping("/close")
    public Map<String, Object> close() {
        projectService.closeProject();
        return Map.of("status", "ok");
    }

    @PutMapping("/{oldProjectName}")
    public ResponseEntity<?> updateProject(
            @PathVariable String oldProjectName,
            @RequestBody UpdateProjectObject newProject) {

        try {

            System.out.println("New Description is "+ newProject.description);
            projectService.updateProject(
                    oldProjectName,
                    newProject.name,
                    newProject.description
            );

            return ResponseEntity.ok(
                    new ApiResponse<>(true, "Project updated successfully", null)
            );

        } catch (IllegalArgumentException e) {
            return ResponseEntity
                    .badRequest()
                    .body(new ApiError(e.getMessage(), "VALIDATION_ERROR"));

        } catch (IOException e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiError("Internal error while updating project", "IO_ERROR"));

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiError("Unexpected error", "UNKNOWN_ERROR"));
        }
    }
    // Liste simple
//    @GetMapping("/list")
//    public List<String> listProjects() {
//        return projectService.listProjects();
//    }

    // Liste détaillée
    @GetMapping("/list/details")
    public List<Map<String, Object>> listProjectsDetailed() {
        return projectService.listProjectsDetailed();
    }

}
