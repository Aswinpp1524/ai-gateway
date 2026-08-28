package dev.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Choice shape on the non-streaming response: a full message, not a delta. */
record OpenAiChoice(OpenAiMessage message, @JsonProperty("finish_reason") String finishReason) {}