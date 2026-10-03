package dev.noturne.core.attach;

/**
 * Identifies this JVM.
 *
 * <p>Needed to keep the injector from listing — and offering to inject into — itself. Uses the
 * Java 9 {@code ProcessHandle} API reflectively so the class still loads on Java 8, with the
 * {@code RuntimeMXBean} name as the fallback.
 */
public final class CurrentProcess {

    private static final int UNKNOWN = -1;

    private CurrentProcess() {
    }

    /** This JVM's PID, or {@code -1} when it cannot be determined. */
    public static int pid() {
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object current = handleClass.getMethod("current").invoke(null);
            Object pid = handleClass.getMethod("pid").invoke(current);
            if (pid instanceof Number) {
                return ((Number) pid).intValue();
            }
        } catch (Throwable ignored) {
            // Java 8, or a restricted runtime: fall through
        }
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return Integer.parseInt(at > 0 ? name.substring(0, at) : name);
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }
}
