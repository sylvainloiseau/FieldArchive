package com.uspn.rdf_back.dtos;

public record ApiError(
        String message,
        String errorCode
) {}
