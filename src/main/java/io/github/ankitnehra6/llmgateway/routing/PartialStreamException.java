package io.github.ankitnehra6.llmgateway.routing;

/**
 * Raised when a stream fails after output has already reached the client.
 *
 * <p>Distinct from {@link AllProvidersFailedException} because nothing can be done about
 * it: the response status and some of the body are already sent, so there is no way to
 * return an error document and no way to fail over without splicing two different answers
 * together. The only honest action is to terminate the stream and let the client see a
 * truncated response.
 */
public class PartialStreamException extends RuntimeException {

    public PartialStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
