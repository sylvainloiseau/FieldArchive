package fr.cnrs.lacito.fieldarchive.dtos;

public class CreateProjectRequest {
    public String name;          // ex: "archive_2025"
    public boolean persistent = true;  // stockage disque

    private String description;

    private String prefix;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isPersistent() { return persistent; }
    public void setPersistent(boolean persistent) { this.persistent = persistent; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPrefix() {
        return prefix;
    }
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }
}


