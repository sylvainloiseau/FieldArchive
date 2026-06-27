package com.uspn.rdf_back.controllers;

import com.uspn.rdf_back.dtos.CreateProjectRequest;
import com.uspn.rdf_back.dtos.ProjectDto;
import com.uspn.rdf_back.services.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.Map;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/open")
    public Map<String, Object> open(@RequestBody CreateProjectRequest req) {
        ProjectDto dto = projectService.openProject(req.getName(), req.isPersistent(), req.getDescription());
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
