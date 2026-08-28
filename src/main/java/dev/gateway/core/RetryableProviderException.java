package dev.gateway.core;

public class RetryableProviderException extends ProviderException {

    public RetryableProviderException(String provider, String message, Throwable cause) {
        super(provider, message, cause);
    }

    @Override
    public boolean retryable() {
        return true;
    }
}