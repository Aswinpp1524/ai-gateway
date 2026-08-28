package dev.gateway.core;

public class TerminalProviderException extends ProviderException {

    public TerminalProviderException(String provider, String message, Throwable cause) {
        super(provider, message, cause);
    }

    @Override
    public boolean retryable() {
        return false;
    }
}