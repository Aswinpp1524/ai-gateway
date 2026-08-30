package dev.gateway.metering;

/**
 * Mirrors dev.gateway.api.ErrorResponse's wire shape ({message, type}). Duplicated rather than
 * imported: metering and api are sibling layers that both depend on core, not on each other.
 */
record ErrorResponse(String message, String type) {}
