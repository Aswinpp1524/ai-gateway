package dev.gateway.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonProperty;

record AnthropicStopReason(@JsonProperty("stop_reason") String stopReason) {}