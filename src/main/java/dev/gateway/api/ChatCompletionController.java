package dev.gateway.api;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.gateway.core.LlmProvider;
import dev.gateway.core.model.ChatRequest;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final List<LlmProvider> providers;
    private final ChatCompletionMapper mapper;

    public ChatCompletionController(List<LlmProvider> providers, ChatCompletionMapper mapper) {
        this.providers = providers;
        this.mapper = mapper;
    }

    @PostMapping("/chat/completions")
    public Mono<ChatCompletionResponse> createChatCompletion(@Valid @RequestBody ChatCompletionRequest request) {
        ChatRequest coreRequest = mapper.toCoreRequest(request);
        return providers.stream()
                .filter(provider -> provider.supports(coreRequest.model()))
                .findFirst()
                .map(provider -> provider.complete(coreRequest).map(mapper::toApiResponse))
                .orElseGet(() -> Mono.error(new NoProviderAvailableException(coreRequest.model())));
    }
}