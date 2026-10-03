package dev.noturne.core.attach;

import java.util.Collections;
import java.util.List;

/** Raised when every {@link AttachStrategy} failed; carries what was tried and why. */
public final class AttachException extends Exception {

    private static final long serialVersionUID = 1L;

    private final List<String> attempted;

    public AttachException(int pid, List<String> attempted, Throwable cause) {
        super("failed to attach to pid " + pid + "; strategies tried: " + attempted, cause);
        this.attempted = Collections.unmodifiableList(attempted);
    }

    public List<String> attempted() {
        return attempted;
    }
}
