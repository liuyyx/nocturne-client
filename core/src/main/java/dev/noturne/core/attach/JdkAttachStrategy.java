package dev.noturne.core.attach;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Attaches through the platform {@code com.sun.tools.attach.VirtualMachine} API.
 *
 * <p>On JDK 9+ that class lives in the {@code jdk.attach} module and is always reachable. On JDK 8
 * it lives in {@code lib/tools.jar}, which is <em>not</em> on the default classpath — so when the
 * direct lookup fails we load that jar into a child class loader and retry. That keeps the
 * injector working under a plain JDK 8 (and reports a precise error when only a JRE is present).
 */
public final class JdkAttachStrategy implements AttachStrategy {

    @Override
    public String name() {
        return "jdk-api";
    }

    @Override
    public void attach(int pid, File agentJar, String options) throws Exception {
        if (!agentJar.isFile()) {
            throw new IllegalArgumentException("agent jar not found: " + agentJar);
        }
        Class<?> vmClass = virtualMachineClass();
        Method attachMethod = vmClass.getMethod("attach", String.class);
        Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
        Method detach = vmClass.getMethod("detach");

        Object vm = attachMethod.invoke(null, Integer.toString(pid));
        try {
            loadAgent.invoke(vm, agentJar.getAbsolutePath(), options == null ? "" : options);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Surface the real failure (no such process, agent jar rejected, …) instead of the
            // reflection wrapper, which tells the user nothing.
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } finally {
            try {
                detach.invoke(vm);
            } catch (Throwable ignored) {
                // a failed detach must not mask a successful load
            }
        }
    }

    /** Resolves {@code com.sun.tools.attach.VirtualMachine}, falling back to JDK 8's tools.jar. */
    private static Class<?> virtualMachineClass() throws Exception {
        try {
            return Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException notFound) {
            File toolsJar = locateToolsJar();
            if (toolsJar == null) {
                throw new ClassNotFoundException(
                        "com.sun.tools.attach.VirtualMachine is not available and no tools.jar was "
                                + "found under " + System.getProperty("java.home"), notFound);
            }
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{toolsJar.toURI().toURL()},
                    JdkAttachStrategy.class.getClassLoader());
            return Class.forName("com.sun.tools.attach.VirtualMachine", true, loader);
        }
    }

    /**
     * JDK 8 keeps the attach API in {@code lib/tools.jar}: at {@code $JAVA_HOME/lib} for a JDK and
     * at {@code $JAVA_HOME/../lib} when running from the bundled {@code jre} directory.
     */
    static File locateToolsJar() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        File base = new File(home);
        File[] candidates = {
                new File(base, "lib/tools.jar"),
                new File(base, "../lib/tools.jar"),
                new File(base, "jre/lib/tools.jar"),
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }
}
