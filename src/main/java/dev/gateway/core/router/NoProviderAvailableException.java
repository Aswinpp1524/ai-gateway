package dev.gateway.core.router;

public class NoProviderAvailableException extends RuntimeException {

    public NoProviderAvailableException(String model) {
        super("No provider available for model: " + model);
    }
}