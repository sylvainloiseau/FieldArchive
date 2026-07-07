package fr.cnrs.lacito.fieldarchive.dtos;

public record ApiResponse<T>(
        boolean success,
        String message,
        T data
) {}
