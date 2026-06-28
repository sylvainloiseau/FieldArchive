package com.uspn.rdf_back.dtos;

public record ApiResponse<T>(
        boolean success,
        String message,
        T data
) {}
