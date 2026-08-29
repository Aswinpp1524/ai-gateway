package dev.gateway.core.resilience;

import java.util.concurrent.TimeoutException;

import dev.gateway.core.RetryableProviderException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/** Shared classification of which failures are worth acting on, and how - used by both the
 * same-provider retry loop here and the cross-provider fallover loop in dev.gateway.core.router. */
public final class TransientFailures {

    private TransientFailures() {}

    /**
     * Worth reattempting the SAME provider. Deliberately excludes an open circuit
     * (CallNotPermittedException) - retrying into a circuit that just tripped wastes an
     * attempt for no chance of success; that case belongs to fallover, not retry.
     */
    public static boolean isRetryEligible(Throwable error) {
        return error instanceof RetryableProviderException || error instanceof TimeoutException;
    }

    /**
     * Worth trying a DIFFERENT provider. Includes an open circuit - "this one is unhealthy,
     * try someone else" is exactly what a tripped breaker means.
     */
    public static boolean isFalloverEligible(Throwable error) {
        return isRetryEligible(error) || error instanceof CallNotPermittedException;
    }
}