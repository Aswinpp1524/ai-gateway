package dev.gateway.tenant;

/**
 * Mirrors dev.gateway.api.ErrorResponse's wire shape ({message, type}). Duplicated rather than
 * imported: tenant and api are sibling layers that both depend on core, not on each other -
 * importing across them here would be the first crack in that rule. Two fields duplicated is
 * cheaper than a dependency that shouldn't exist.
 */
record ErrorResponse(String message, String type) {}
