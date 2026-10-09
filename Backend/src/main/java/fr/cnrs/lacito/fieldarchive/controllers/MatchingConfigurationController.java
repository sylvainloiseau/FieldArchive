package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationEnvelopeDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDtos.*;
import fr.cnrs.lacito.fieldarchive.dtos.matching.ValidationReportDto;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.matching.MatchingPreviewService;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationLoader;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationService;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationValidator;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingVocabularyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The data matching configuration of the open project, and what the settings page needs to edit it. */
@RestController
@RequestMapping("/matching/configuration")
public class MatchingConfigurationController {

    private final MatchingConfigurationService configurationService;
    private final MatchingVocabularyService vocabularyService;
    private final MatchingPreviewService previewService;

    public MatchingConfigurationController(MatchingConfigurationService configurationService,
                                           MatchingVocabularyService vocabularyService,
                                           MatchingPreviewService previewService) {
        this.configurationService = configurationService;
        this.vocabularyService = vocabularyService;
        this.previewService = previewService;
    }

    @GetMapping
    public MatchingConfigurationEnvelopeDto get() {
        return configurationService.envelope(configurationService.currentConfiguration(), configurationService.origin(),
                vocabularyService.dataContext());
    }

    @GetMapping("/default")
    public MatchingConfigurationEnvelopeDto getDefault() {
        return configurationService.envelope(configurationService.defaultConfiguration(), "default",
                vocabularyService.dataContext());
    }

    @PutMapping
    public MatchingConfigurationEnvelopeDto save(@RequestBody MatchingConfigurationDto configuration) {
        MatchingConfigurationDto saved = configurationService.save(configuration);
        return configurationService.envelope(saved, "project", vocabularyService.dataContext());
    }

    @DeleteMapping
    public MatchingConfigurationEnvelopeDto reset() {
        configurationService.reset();
        return getDefault();
    }

    @PostMapping("/validate")
    public ValidationReportDto validate(@RequestBody MatchingConfigurationDto configuration) {
        return MatchingConfigurationValidator.validate(MatchingConfigurationLoader.normalize(configuration),
                vocabularyService.dataContext());
    }

    @GetMapping("/vocabulary/types")
    public List<TypeCandidateDto> types() {
        return vocabularyService.types();
    }

    @GetMapping("/vocabulary/predicates")
    public List<PredicateCandidateDto> predicates(@RequestParam(required = false) List<String> types) {
        return vocabularyService.predicates(types);
    }

    @PostMapping("/coverage")
    public GroupCoverageDto coverage(@RequestBody CoverageRequest request) {
        return vocabularyService.coverage(request);
    }

    @PostMapping("/preview")
    public MatchingPreviewDto preview(@RequestBody MatchingPreviewRequest request) {
        return previewService.preview(request);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + MatchingConfigurationService.FILE_NAME + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(configurationService.exportBytes());
    }

    /** Parses and validates an uploaded configuration; nothing is saved. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MatchingConfigurationEnvelopeDto importFile(@RequestPart("file") MultipartFile file) {
        try {
            MatchingConfigurationDto dto = configurationService.parseImport(new String(file.getBytes(), StandardCharsets.UTF_8));
            return configurationService.envelope(dto, "import", vocabularyService.dataContext());
        } catch (IOException e) {
            throw new BadRequestException("Cannot read the uploaded file: " + e.getMessage());
        }
    }
}
