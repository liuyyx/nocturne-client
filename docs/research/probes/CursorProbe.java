import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;

/**
 * 隔离探针：LWJGL 3.3.3 的 glfwGetCursorPos 两种重载（double[] 与 DoubleBuffer）行为对比。
 * 目的：确认 ReflectiveInput 读回野值（32572,32531）是不是「数组重载在 3.3.3 上不写回」。
 */
public final class CursorProbe {
    public static void main(String[] args) {
        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long win = GLFW.glfwCreateWindow(400, 300, "cursor-probe", 0L, 0L);
        if (win == 0L) {
            System.out.println("window create failed");
            return;
        }
        GLFW.glfwSetCursorPos(win, 123.0, 77.0);
        System.out.println("setCursorPos -> 123,77");

        double[] ax = new double[]{-1.0, -1.0};
        double[] ay = new double[]{-1.0, -1.0};
        GLFW.glfwGetCursorPos(win, ax, ay);
        System.out.println("array overload     -> x=" + ax[0] + " y=" + ay[0]
                + "  (x[1]=" + ax[1] + ", y[1]=" + ay[1] + ")");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer bx = stack.mallocDouble(1);
            DoubleBuffer by = stack.mallocDouble(1);
            bx.put(0, -1.0);
            by.put(0, -1.0);
            GLFW.glfwGetCursorPos(win, bx, by);
            System.out.println("stack DoubleBuffer -> x=" + bx.get(0) + " y=" + by.get(0));
        }

        System.out.println("windowSize array   ->");
        int[] w = new int[2];
        int[] h = new int[2];
        GLFW.glfwGetWindowSize(win, w, h);
        System.out.println("   " + w[0] + "x" + h[0] + " (second slots " + w[1] + "," + h[1] + ")");
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
    }
}
