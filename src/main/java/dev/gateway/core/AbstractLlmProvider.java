package dev.gateway.core;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.netty.handler.timeout.ReadTimeoutException;
import reactor.netty.http.client.HttpClient;

/**
 * Base class for provider adapters. Holds the WebClient used for all calls to
 * the provider's API and the HTTP-status/timeout classification shared by
 * every adapter's error mapping.
 */
public abstract class AbstractLlmProvider implements LlmProvider {

    protected final WebClient webClient;

    protected AbstractLlmProvider(WebClient.Builder builder, String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.create().responseTimeout(timeout);
        this.webClient = builder
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** Classifies a failure from a provider call as retryable or terminal. */
    protected ProviderException classifyError(Throwable error) {
        if (error instanceof ProviderException providerException) {
            return providerException;
        }
        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            String message = "Provider returned status " + status + ": " + responseException.getResponseBodyAsString();
            return isRetryable(status, responseException)
                    ? new RetryableProviderException(name(), message, error)
                    : new TerminalProviderException(name(), message, error);
        }
        if (error instanceof WebClientRequestException
                || error instanceof TimeoutException
                || error instanceof ReadTimeoutException) {
            return new RetryableProviderException(name(), "Provider request failed: " + error.getMessage(), error);
        }
        return new TerminalProviderException(name(), "Unexpected provider error: " + error.getMessage(), error);
    }

    /**
     * Whether an HTTP error response should be treated as retryable. Default is status-code-only
     * (429 and 5xx retryable, other 4xx terminal); override when a provider's error body carries a
     * more specific signal than the status code alone (e.g. distinguishing an exhausted quota, which
     * won't recover on retry, from a transient rate limit, which will).
     */
    protected boolean isRetryable(int status, WebClientResponseException responseException) {
        return status == 429 || status >= 500;
    }
}