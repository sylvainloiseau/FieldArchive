package fr.cnrs.lacito.fieldarchive.dtos;



public class DataSourceDto {
    public String id;
    public String shortName;
    public String longName;
    public String description;

    public String type;          // internal | external
    public boolean editable;

    public String graphIri;      // urn:datasource:xxx
    public String sourceTool;    // Tropy, Lameta, Gramps
    public String sourceLocation;// URL / path

    public String createdAt;
    public String lastSync;
}