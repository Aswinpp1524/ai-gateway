package dev.gateway.core;

public abstract class ProviderException extends RuntimeException {
    private final String provider;

    protected ProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }

    /** Whether retrying this request could succeed. */
    public abstract boolean retryable();
}