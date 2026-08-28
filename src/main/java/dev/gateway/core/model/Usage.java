package dev.gateway.core.model;

public record Usage(int promptTokens, int completionTokens, boolean estimated) {

    public static Usage exact(int prompt, int completion) {
        return new Usage(prompt, completion, false);
    }

    public static Usage estimated(int prompt, int completion) {
        return new Usage(prompt, completion, true);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}