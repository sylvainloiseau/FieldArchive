package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.dtos.ImporterDtos.ImporterDto;
import fr.cnrs.lacito.fieldarchive.dtos.ImporterDtos.ImporterOptionDto;
import fr.cnrs.lacito.fieldarchive.dtos.ImporterDtos.ImportersDto;
import fr.cnrs.lacito.fieldarchive.dtos.ImporterDtos.PluginDirectoriesDto;
import fr.cnrs.lacito.fieldarchive.dtos.ImporterDtos.PluginProblemDto;
import fr.cnrs.lacito.fieldarchive.services.importer.Importer;
import fr.cnrs.lacito.fieldarchive.services.importer.ImporterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/** The importers of external data sources: "Sync with an RDF file" and the plugins. No project needed. */
@RestController
@RequestMapping("/importers")
public class ImporterController {

    private final ImporterRegistry registry;

    public ImporterController(ImporterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ImportersDto list() {
        return toDto(registry.snapshot());
    }

    /** Scans the plugin directories again (a plugin was added or removed). */
    @PostMapping("/rescan")
    public ImportersDto rescan() {
        return toDto(registry.rescan());
    }

    private static ImportersDto toDto(ImporterRegistry.Snapshot snapshot) {
        return new ImportersDto(
                snapshot.importers().stream().map(ImporterController::toDto).toList(),
                snapshot.problems().stream().map(p -> new PluginProblemDto(p.location(), p.message(), p.error())).toList(),
                new PluginDirectoriesDto(string(snapshot.bundledDirectory()), string(snapshot.userDirectory())));
    }

    private static ImporterDto toDto(Importer i) {
        return new ImporterDto(i.id(), i.label(), i.description(),
                i.options().stream().map(o -> new ImporterOptionDto(o.name(), o.description(), o.defaultValue())).toList(),
                i.inputKind().name(), i.origin().name(), i.version(), string(i.location()));
    }

    private static String string(Path path) {
        return path == null ? null : path.toAbsolutePath().toString();
    }
}
