package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.SparqlRequest;
import fr.cnrs.lacito.fieldarchive.services.SparqlService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.NlQueryHistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sparql")
public class SparqlController {

    private final SparqlService service;
    private final NlQueryHistoryService historyService;

    public SparqlController(SparqlService service, NlQueryHistoryService historyService) {
        this.service = service;
        this.historyService = historyService;
    }

    /** {@code nl} is present only when the query comes from a natural-language question: it is then recorded. */
    @PostMapping("/select")
    public List<Map<String, String>> select(@RequestBody SparqlRequest body) {
        List<Map<String, String>> rows = service.select(body.query);
        if (body.nl != null) historyService.record(body.nl, body.query, "SELECT", rows.size());
        return rows;
    }

    @PostMapping("/update")
    public void update(@RequestBody SparqlRequest body){
        // Entity names as they were when the question was asked (a rename changes them).
        var entitiesBefore = body.nl != null ? historyService.entitiesIn(body.query) : null;
        service.update(body.query);
        if (body.nl != null) historyService.record(body.nl, body.query, "UPDATE", null, entitiesBefore);
    }
}
