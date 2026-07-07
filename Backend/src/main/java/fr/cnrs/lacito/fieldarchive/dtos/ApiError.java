package fr.cnrs.lacito.fieldarchive.dtos;

public record ApiError(
        String message,
        String errorCode
) {}
