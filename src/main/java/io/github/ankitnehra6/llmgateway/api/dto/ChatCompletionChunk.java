package io.github.ankitnehra6.llmgateway.api.dto;

import java.util.List;

/**
 * One server-sent event in a streamed completion.
 *
 * <p>Shaped like an OpenAI {@code chat.completion.chunk} so existing streaming clients
 * parse it unchanged. The stream ends with a chunk carrying a {@code finish_reason} and
 * then the literal {@code data: [DONE]} sentinel those clients expect.
 *
 * @param id shared by every chunk in one response
 * @param object always {@code chat.completion.chunk}
 * @param model the model serving the request
 * @param choices exactly one, since this gateway does not fan out to n completions
 */
public record ChatCompletionChunk(
        String id, String object, String model, List<Choice> choices) {

    public static ChatCompletionChunk content(String id, String model, String text) {
        return new ChatCompletionChunk(
                id, "chat.completion.chunk", model, List.of(new Choice(0, new Delta(text), null)));
    }

    /** The terminating chunk: an empty delta plus a reason the generation stopped. */
    public static ChatCompletionChunk done(String id, String model, String finishReason) {
        return new ChatCompletionChunk(
                id,
                "chat.completion.chunk",
                model,
                List.of(new Choice(0, new Delta(null), finishReason)));
    }

    /**
     * @param index always 0
     * @param delta the incremental text
     * @param finishReason null until the final chunk
     */
    public record Choice(int index, Delta delta, String finishReason) {}

    /** The text added by this chunk. Null on the terminating chunk. */
    public record Delta(String content) {}
}
