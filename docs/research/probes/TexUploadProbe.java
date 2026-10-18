import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * 决定性实验：在真实 GL 上下文里复现「GL_UNPACK_ROW_LENGTH 非 0 时上传小纹理」的行为。
 *
 * <p>论题（1.21.11 真机 ACCESS_VIOLATION 的根因假设）：游戏把 {@code GL_UNPACK_ROW_LENGTH}
 * 设成图集宽度后没还原，驱动按「每行 N 像素」去读我们的 1×1 缓冲 → 读越界 → 崩在 nvoglv64。
 *
 * <p>三个 case，各自独立进程也安全（本进程崩了就是假设成立的证据）：
 * <ol>
 *   <li>ROW_LENGTH=0（我们的修复）→ 预期正常；</li>
 *   <li>ROW_LENGTH=4096（模拟游戏留下的状态）→ 预期驱动读越界；</li>
 *   <li>复位 ROW_LENGTH=0 后再传一张 512×512 → 预期恢复正常。</li>
 * </ol>
 */
public final class TexUploadProbe {
    public static void main(String[] args) {
        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long win = GLFW.glfwCreateWindow(64, 64, "tex-probe", 0L, 0L);
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_VERSION)
                + " | " + GL11.glGetString(GL11.GL_VENDOR));

        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);

        System.out.println("[case1] ROW_LENGTH=0, upload 1x1 ...");
        uploadUnpack(0, 1, 1);
        System.out.println("[case1] ok; glGetError=" + GL11.glGetError());

        System.out.println("[case2] ROW_LENGTH=4096, upload 1x1 (expect driver fault) ...");
        uploadUnpack(4096, 1, 1);
        System.out.println("[case2] survived; glGetError=" + GL11.glGetError());

        System.out.println("[case3] ROW_LENGTH=0 again, upload 512x512 ...");
        uploadUnpack(0, 512, 512);
        System.out.println("[case3] ok; glGetError=" + GL11.glGetError());

        // case4：模拟 Blaze3D 留下的 PBO —— 上传时 GL_PIXEL_UNPACK_BUFFER 仍被绑定。
        // 此时 glTexImage2D 的最后一个参数被驱动当作「PBO 内偏移」，而不是客户端内存指针。
        int pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(0x88EC, pbo);
        GL15.glBufferData(0x88EC, 16L, GL15.GL_STATIC_DRAW);
        System.out.println("[case4] PBO bound (size=16), upload 1x1 with client pointer (expect driver fault) ...");
        uploadUnpack(0, 1, 1);
        System.out.println("[case4] survived; glGetError=" + GL11.glGetError());
        GL15.glBindBuffer(0x88EC, 0);

        // case5：真机实测到的游戏遗留状态（1.21.11 首次上传时）——ROW_LENGTH=1152、
        // SKIP_ROWS=864、SKIP_PIXELS=288。按 GL 规格，源地址 = base + ((SKIP_ROWS*ROW_LENGTH)
        // + SKIP_PIXELS) * 4 字节 ≈ base + 3.98 MB，而我们只有 4 字节 → 读未映射内存。
        System.out.println("[case5] game-observed state (1152/864/288), upload 1x1 (expect driver fault) ...");
        ByteBuffer tiny = org.lwjgl.system.MemoryUtil.memAlloc(4);
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(0x0CF2, 1152);
            GL11.glPixelStorei(0x0CF3, 864);
            GL11.glPixelStorei(0x0CF4, 288);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, tiny);
            System.out.println("[case5] survived; glGetError=" + GL11.glGetError());
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(tiny);
            GL11.glPixelStorei(0x0CF2, 0);
            GL11.glPixelStorei(0x0CF3, 0);
            GL11.glPixelStorei(0x0CF4, 0);
        }

        // case6：证明「解包状态会移动源地址」——把红像素放在 ((864*1152)+288)*4 处，
        // 其余为 0；若上传后 1x1 纹素是红的，就说明驱动确实从那个偏移处读（而不是从 buf[0]）。
        int skipOffset = ((864 * 1152) + 288) * 4;
        ByteBuffer big = org.lwjgl.system.MemoryUtil.memCalloc(8 * 1024 * 1024);
        big.putInt(skipOffset, 0xFF0000FF);            // RGBA = 红
        System.out.println("[case6] big buffer, red pixel placed at offset " + skipOffset);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(0x0CF2, 1152);
        GL11.glPixelStorei(0x0CF3, 864);
        GL11.glPixelStorei(0x0CF4, 288);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, big);
        GL11.glPixelStorei(0x0CF2, 0);
        GL11.glPixelStorei(0x0CF3, 0);
        GL11.glPixelStorei(0x0CF4, 0);
        ByteBuffer readBack = org.lwjgl.system.MemoryUtil.memAlloc(4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBack);
        System.out.printf("[case6] texel read back = 0x%08X (expect 0xFF0000FF if offset honoured)%n",
                readBack.getInt(0));
        org.lwjgl.system.MemoryUtil.memFree(readBack);
        org.lwjgl.system.MemoryUtil.memFree(big);

        System.out.println("PROBE END");
        GLFW.glfwTerminate();
    }

    private static void uploadUnpack(int rowLength, int w, int h) {
        ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(0x0CF2 /* GL_UNPACK_ROW_LENGTH */, rowLength);
            GL11.glPixelStorei(0x0CF3 /* GL_UNPACK_SKIP_ROWS */, 0);
            GL11.glPixelStorei(0x0CF4 /* GL_UNPACK_SKIP_PIXELS */, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
    }
}
