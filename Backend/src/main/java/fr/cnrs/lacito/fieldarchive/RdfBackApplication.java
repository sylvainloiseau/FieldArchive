package fr.cnrs.lacito.fieldarchive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class RdfBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(RdfBackApplication.class, args);
        String dataPath = System.getenv("FIELD_ARCHIVE_DATA");

        if (dataPath == null) {
            throw new IllegalStateException("FIELD_ARCHIVE_DATA is not defined");
        }

        Path projectsPath = Paths.get(dataPath, "projects");

        try {
            Files.createDirectories(projectsPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create projects directory : " + projectsPath, e);
        }

        System.out.println("Projects directory: " + projectsPath);
    }

}
