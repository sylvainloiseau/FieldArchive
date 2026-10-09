package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.List;

/** An inconsistency between the links, reported to the user and never repaired automatically. */
public class ConflictDto {
    /** SAME_SOURCE_IN_CLUSTER | REJECTED_INSIDE_CLUSTER */
    public String kind;
    public List<EntityNodeDto> nodes = new ArrayList<>();
    public List<String> linkIds = new ArrayList<>();
    public String message;
}
