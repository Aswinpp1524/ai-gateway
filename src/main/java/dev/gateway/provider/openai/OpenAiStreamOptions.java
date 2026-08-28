package dev.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

record OpenAiStreamOptions(@JsonProperty("include_usage") boolean includeUsage) {}