package dev.gateway.core.model;

public record Message(Role role, String content) {

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }
}