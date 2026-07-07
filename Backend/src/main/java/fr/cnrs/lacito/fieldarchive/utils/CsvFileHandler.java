package fr.cnrs.lacito.fieldarchive.utils;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Service
public class CsvFileHandler {

    public void appendToCsv(String filePath, String data) throws IOException {
        Path path = Paths.get(filePath);

        // Check if file exists
        boolean fileExists = Files.exists(path);

        // Prepare data to write
        String dataToWrite = data;

        // If file doesn't exist, you might want to add headers first
        if (!fileExists) {
            // Option 1: Add headers
            String headers = "url,name,description,creationDate,lastSynchDate\n";
            Files.write(path, headers.getBytes(), StandardOpenOption.CREATE);
            fileExists = true;
        }

        // Append data to the file
        Files.write(path, dataToWrite.getBytes(),
                StandardOpenOption.APPEND,
                StandardOpenOption.CREATE);
    }
}