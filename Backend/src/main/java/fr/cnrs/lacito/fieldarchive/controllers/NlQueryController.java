package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.services.nlquery.NlQueryHistoryService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.NlQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Natural-language questions translated into SPARQL by an agent (Claude, ChatGPT, local model, built-in). */
@RestController
@RequestMapping("/nlquery")
public class NlQueryController {

    private final NlQueryService service;
    private final NlQueryHistoryService historyService;

    public NlQueryController(NlQueryService service, NlQueryHistoryService historyService) {
        this.service = service;
        this.historyService = historyService;
    }

    @GetMapping("/status")
    public NlQueryStatusDto status() {
        return service.status();
    }

    @PostMapping("/translate")
    public NlQueryDto translate(@RequestBody NlQueryRequest request) {
        return service.translate(request);
    }

    @PostMapping("/cancel/{requestId}")
    public void cancel(@PathVariable String requestId) {
        service.cancel(requestId);
    }

    @GetMapping("/history")
    public List<NlHistoryEntry> history() {
        return historyService.list();
    }

    @DeleteMapping("/history/{id}")
    public void deleteHistoryEntry(@PathVariable String id) {
        historyService.delete(id);
    }

    @PutMapping("/keys")
    public void saveKey(@RequestBody SaveApiKeyRequest request) {
        service.saveKey(request);
    }

    @DeleteMapping("/keys/{provider}")
    public void deleteKey(@PathVariable String provider) {
        service.deleteKey(provider);
    }
}
