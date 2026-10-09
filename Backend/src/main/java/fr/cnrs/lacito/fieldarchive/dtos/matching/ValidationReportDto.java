package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.List;

/** Result of validating a matching configuration; errors block saving, warnings do not. */
public class ValidationReportDto {
    public List<Issue> errors = new ArrayList<>();
    public List<Issue> warnings = new ArrayList<>();

    public static class Issue {
        /** Location in the configuration, e.g. {@code equivalentGroups[2].properties.name.weight}. */
        public String path;
        public String code;
        public String message;

        public Issue() {}

        public Issue(String path, String code, String message) {
            this.path = path;
            this.code = code;
            this.message = message;
        }
    }

    public void error(String path, String code, String message) {
        errors.add(new Issue(path, code, message));
    }

    public void warning(String path, String code, String message) {
        warnings.add(new Issue(path, code, message));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
