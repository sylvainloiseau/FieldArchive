package fr.cnrs.lacito.fieldarchive.dtos;

public class ProviderStatusDto {
    public String id;
    public String label;
    public String model;
    public boolean available;
    public boolean free;
    public String keySource;   // environment | settings | null
    public String reason;
}
