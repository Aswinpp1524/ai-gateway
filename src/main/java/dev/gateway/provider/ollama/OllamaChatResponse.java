package dev.gateway.provider.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Shape of both a streaming NDJSON line and the whole non-streaming response.
 * On a delta line: message.content is set, done=false, the done/eval fields are null.
 * On the terminal line (done=true): message.content is "", done_reason and the eval
 * counts are populated.
 */
record OllamaChatResponse(
        String model,
        OllamaMessage message,
        boolean done,
        @JsonProperty("done_reason") String doneReason,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount,
        @JsonProperty("eval_count") Integer evalCount
) {}