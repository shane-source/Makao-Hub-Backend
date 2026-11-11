package com.makaohub.backend.renter.api;

import jakarta.validation.constraints.NotNull;

/** Missing/null is invalid; it must never accidentally become False or authorize a payout. */
public record MoveInResponseRequest(@NotNull Boolean accepted) { }
