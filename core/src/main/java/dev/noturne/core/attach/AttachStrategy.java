package dev.noturne.core.attach;

import java.io.File;

/**
 * One way to hand an agent jar to an already-running JVM.
 *
 * <p>Implementations are tried in order; the first that succeeds wins. This keeps the
 * standard JDK path fast while leaving room for the self-contained paths (shadow
 * {@code sun.tools.attach.*} bytecode and a native attach helper) that cover JREs where
 * {@code jdk.attach} / {@code tools.jar} is missing.
 */
public interface AttachStrategy {

    /** Short human-readable name, used in diagnostics. */
    String name();

    /**
     * Loads {@code agentJar} into the JVM identified by {@code pid}.
     *
     * @throws Exception when this strategy cannot be used or fails; the caller tries the next one
     */
    void attach(int pid, File agentJar, String options) throws Exception;
}
