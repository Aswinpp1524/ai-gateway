package dev.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Choice shape on a streaming chunk: a partial delta, not a full message. */
record OpenAiChoiceDelta(OpenAiDelta delta, @JsonProperty("finish_reason") String finishReason) {}