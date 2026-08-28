package dev.gateway.provider.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;

record
OllamaOptions(
        Double temperature,
        @JsonProperty("num_predict") Integer numPredict
) {}