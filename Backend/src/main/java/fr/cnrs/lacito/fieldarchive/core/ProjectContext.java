package fr.cnrs.lacito.fieldarchive.core;

import org.eclipse.rdf4j.repository.Repository;

public final class ProjectContext {
    private static Repository repository;
    private static String projectName;

    private ProjectContext() {}

    public static void set(Repository repo, String name) {
        repository = repo;
        projectName = name;
    }

    public static Repository getRepository() {
        return repository;
    }

    public static String getProjectName() {
        return projectName;
    }

    public static boolean isOpen() {
        return repository != null;
    }

    public static void close() {
        if (repository != null) {
            repository.shutDown();
        }
        repository = null;
        projectName = null;
    }
}