package org.lwjgl.opengl;

import dev.nocturne.ui.gl.FakeGl;

/** OpenGL 1.3 的真实声明位置：服务端与客户态活动纹理单元。 */
public final class GL13 {

    private GL13() {
    }

    public static void glActiveTexture(int unit) {
        GL11.activeTexture = unit;
        FakeGl.activeTextureCalls++;
        FakeGl.lastActiveTexture = unit;
        FakeGl.events.add("activeTexture:" + unit);
    }

    public static void glClientActiveTexture(int unit) {
        FakeGl.clientActiveTextureCalls++;
    }
}
