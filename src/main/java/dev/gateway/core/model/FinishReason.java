package dev.gateway.core.model;

public enum FinishReason {
    STOP,           // model finished naturally
    LENGTH,         // hit max_tokens
    CONTENT_FILTER, // provider refused
    CANCELLED,      // client disconnected
    ERROR
}