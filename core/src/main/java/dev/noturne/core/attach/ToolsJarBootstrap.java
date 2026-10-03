package dev.noturne.core.attach;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JDK 8 keeps the attach API in {@code lib/tools.jar}, which is not on the default classpath.
 * Loading that jar through a child class loader is not enough: the {@code AttachProvider}
 * {@link java.util.ServiceLoader} lookup then runs inside that loader and fails.
 *
 * <p>So instead we restart this JVM once with {@code tools.jar} appended to the classpath. On
 * JDK 9+ the attach API is already present and this is a no-op.
 */
public final class ToolsJarBootstrap {

    private static final String ATTACH_CLASS = "com.sun.tools.attach.VirtualMachine";

    private ToolsJarBootstrap() {
    }

    /**
     * @return {@code true} when a replacement process was started and the caller must return
     *         immediately (the current process is about to be abandoned)
     */
    public static boolean relaunchIfNeeded(String mainClass, String[] args) {
        if (isAttachAvailable()) {
            return false;
        }
        File toolsJar = JdkAttachStrategy.locateToolsJar();
        if (toolsJar == null) {
            return false; // a JRE: nothing to add, the caller will report the failure
        }
        try {
            File self = selfCodeSource();
            File java = javaExecutable();
            List<String> command = new ArrayList<String>();
            command.add(java.getAbsolutePath());
            command.add("-cp");
            command.add(self.getAbsolutePath() + File.pathSeparator + toolsJar.getAbsolutePath());
            command.add(mainClass);
            if (args != null) {
                command.addAll(Arrays.asList(args));
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.inheritIO();
            builder.start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAttachAvailable() {
        try {
            Class.forName(ATTACH_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static File javaExecutable() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File bin = new File(home, "bin");
        File executable = new File(bin, windows ? "java.exe" : "java");
        return executable.isFile() ? executable : new File(bin, "java");
    }

    private static File selfCodeSource() throws Exception {
        String path = ToolsJarBootstrap.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return new File(URLDecoder.decode(path, "UTF-8"));
    }
}
