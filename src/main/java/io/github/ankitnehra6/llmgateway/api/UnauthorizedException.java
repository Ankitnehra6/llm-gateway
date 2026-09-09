package io.github.ankitnehra6.llmgateway.api;

/** Raised when a request carries no usable API key. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
