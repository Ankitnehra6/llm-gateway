package io.github.ankitnehra6.llmgateway.provider;

/** A single turn in a conversation. */
public record ChatMessage(Role role, String content) {

    public ChatMessage {
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
