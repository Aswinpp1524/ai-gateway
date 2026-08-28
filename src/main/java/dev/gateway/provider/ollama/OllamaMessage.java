package dev.gateway.provider.ollama;

/** Same shape for both an outgoing message and the "message" object on an incoming line. */
record OllamaMessage(String role, String content) {}