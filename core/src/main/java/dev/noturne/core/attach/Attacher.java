package dev.noturne.core.attach;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads the noturne agent into a running JVM.
 *
 * <p>Strategies are tried in order and the first success wins. The standard JDK attach API
 * is tried first because it is fastest and always correct when present; self-contained
 * strategies for trimmed JREs are layered on behind the same {@link AttachStrategy} seam.
 */
public final class Attacher {

    /** Agent option carrying the per-session handshake token to the injected side. */
    public static final String OPTION_TOKEN = "token=";

    private static final List<AttachStrategy> STRATEGIES = defaultStrategies();

    private Attacher() {
    }

    private static List<AttachStrategy> defaultStrategies() {
        List<AttachStrategy> strategies = new ArrayList<AttachStrategy>();
        strategies.add(new JdkAttachStrategy());
        // Later phases append: shadow sun.tools.attach.* bytecode (no tools.jar / jdk.attach),
        // then a native attach helper for trimmed JREs.
        return Collections.unmodifiableList(strategies);
    }

    /** Names of the strategies that will be attempted, in order. */
    public static List<String> strategyNames() {
        List<String> names = new ArrayList<String>(STRATEGIES.size());
        for (AttachStrategy strategy : STRATEGIES) {
            names.add(strategy.name());
        }
        return names;
    }

    public static void attach(int pid, File agentJar, String options) throws AttachException {
        List<String> attempted = new ArrayList<String>();
        Throwable last = null;
        for (AttachStrategy strategy : STRATEGIES) {
            attempted.add(strategy.name());
            try {
                strategy.attach(pid, agentJar, options);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new AttachException(pid, attempted, last);
    }
}
