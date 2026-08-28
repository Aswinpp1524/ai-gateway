package dev.gateway.core.model;

public record ChatChunk(
        String delta,
        boolean last,
        Usage usage,
        FinishReason finishReason
) {
    public static ChatChunk of(String delta) {
        return new ChatChunk(delta, false, null, null);
    }

    public static ChatChunk terminal(Usage usage, FinishReason reason) {
        return new ChatChunk("", true, usage, reason);
    }
}