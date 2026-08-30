package dev.gateway.core.model;

public record ChatChunk(
        String delta,
        boolean last,
        Usage usage,
        FinishReason finishReason,
        String servedBy
) {
    public static ChatChunk of(String delta) {
        return new ChatChunk(delta, false, null, null, null);
    }

    public static ChatChunk terminal(Usage usage, FinishReason reason, String servedBy) {
        return new ChatChunk("", true, usage, reason, servedBy);
    }
}