package dev.gateway.provider.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
record OllamaOptions(
        Double temperature,
        @JsonProperty("num_predict") Integer numPredict
) {}