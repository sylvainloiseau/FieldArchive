package fr.cnrs.lacito.fieldarchive.utils;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class ProjectsDirectory {

    public Path getPublicPath() {
        String dataPath = System.getenv("FIELD_ARCHIVE_DATA");

        if (dataPath == null) {
            throw new IllegalStateException("FIELD_ARCHIVE_DATA is not defined");
        }

        return Paths.get(dataPath, "projects");
    }
}
