package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchStatus;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDtos.*;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingRunSummaryDto;
import fr.cnrs.lacito.fieldarchive.services.matching.MatchingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Data matching: runs, review of the links, manual links, comparison, clusters. */
@RestController
@RequestMapping("/matching")
public class MatchingController {

    private final MatchingService service;

    public MatchingController(MatchingService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public MatchingOverviewDto overview() {
        return service.getOverview();
    }

    @PostMapping("/runs")
    public List<MatchingRunSummaryDto> run(@RequestBody MatchingRunRequest request) {
        return service.runMatching(request);
    }

    @GetMapping("/links")
    public MatchLinkPageDto links(@RequestParam(required = false) String dataSourceA,
                                  @RequestParam(required = false) String dataSourceB,
                                  @RequestParam(required = false) List<MatchStatus> status,
                                  @RequestParam(required = false) String typeGroupId,
                                  @RequestParam(required = false) Double minScore,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "false") boolean includeSuperseded,
                                  @RequestParam(defaultValue = "true") boolean includeOrphaned,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "200") int limit) {
        return service.listLinks(dataSourceA, dataSourceB, status, typeGroupId, minScore, q,
                includeSuperseded, includeOrphaned, offset, limit);
    }

    @PostMapping("/links/{id}/actions")
    public LinkChangeResultDto action(@PathVariable String id, @RequestBody LinkActionRequest request,
                                      @RequestParam(defaultValue = "false") boolean dryRun) {
        return service.changeLinkStatus(id, request.action, request.note, dryRun);
    }

    @PostMapping("/links/actions")
    public LinkChangeResultDto bulkAction(@RequestBody BulkLinkActionRequest request,
                                          @RequestParam(defaultValue = "false") boolean dryRun) {
        return service.changeLinkStatusBulk(request.ids, request.action, dryRun);
    }

    @PutMapping("/links/{id}/note")
    public LinkChangeResultDto note(@PathVariable String id, @RequestBody NoteRequest request) {
        return service.updateNote(id, request.note);
    }

    @PostMapping("/links")
    public LinkChangeResultDto createManual(@RequestBody CreateManualMatchRequest request,
                                            @RequestParam(defaultValue = "false") boolean dryRun) {
        return service.createManualLink(request, dryRun);
    }

    @DeleteMapping("/links/orphaned")
    public LinkChangeResultDto removeOrphaned(@RequestParam(required = false) String pair,
                                              @RequestParam(required = false) String dataSource,
                                              @RequestParam(defaultValue = "false") boolean dryRun) {
        return service.removeOrphanedLinks(pair, dataSource, dryRun);
    }

    @GetMapping("/compare")
    public PairComparisonDto compare(@RequestParam String dataSourceA, @RequestParam String iriA,
                                     @RequestParam String dataSourceB, @RequestParam String iriB) {
        return service.comparePair(dataSourceA, iriA, dataSourceB, iriB);
    }

    @GetMapping("/entities")
    public List<MatchableEntityDto> entities(@RequestParam String dataSource,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) String typeGroupId,
                                             @RequestParam(required = false) String unmatchedWith,
                                             @RequestParam(defaultValue = "50") int limit) {
        return service.searchEntities(dataSource, q, typeGroupId, unmatchedWith, limit);
    }

    @GetMapping("/clusters")
    public List<MatchClusterDto> clusters(@RequestParam(defaultValue = "3") int minSize) {
        return service.getClusters(minSize);
    }
}
