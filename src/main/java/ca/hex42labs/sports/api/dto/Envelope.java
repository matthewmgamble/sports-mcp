package ca.hex42labs.sports.api.dto;

/** The response shape shared by REST and MCP structured content. */
public record Envelope<T>(T data, Meta meta) {}
