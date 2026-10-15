package com.makaohub.backend.shared.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PaginationPolicy {

    private PaginationPolicy() {
    }

    public static Pageable pageRequest(
            int page,
            int size,
            int maximumSize,
            Sort sort
    ) {
        if (page < 0) {
            throw badRequest("Page must be zero or greater.");
        }

        if (size < 1 || size > maximumSize) {
            throw badRequest(
                    "Page size must be between 1 and "
                            + maximumSize
                            + "."
            );
        }

        return PageRequest.of(page, size, sort);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}
