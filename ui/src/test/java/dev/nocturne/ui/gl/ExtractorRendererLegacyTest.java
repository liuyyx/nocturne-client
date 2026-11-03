package dev.nocturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.ui.render.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 1.16–1.19 的 GUI 绘制形态：绘制原语是「静态方法 + 首参绘制栈」。
 *
 * <p>这一形态与 1.20+ 的 {@code GuiGraphics} 实例方法只差调用口径，但口径错了就是
 * {@code IllegalArgumentException}（实参数量/类型对不上），界面一片空白；因此逐项钉住
 * 实参顺序，而不只是「绑定成功」。
 */
class ExtractorRendererLegacyTest {

    /** 绘制栈：只需是个对象，用来核对「首参就是本帧 setFrame 的那个实例」。 */
    public static final class FakePoseStack {
    }

    /** 字体：{@code width(String)} 是 bindLegacy 的兜底判据。 */
    public static final class FakeFont {
        public int width(String text) {
            return text.length() * 6;
        }
    }

    /**
     * 1.16–1.19 的 {@code Gui}：fill/drawString/scissor 全是静态方法，首参绘制栈。
     * 静态记录刻意放在测试内，便于直接核对实参顺序。
     */
    public static final class FakeLegacyGui {
        static final List<Object[]> CALLS = new ArrayList<>();

        public static void fill(FakePoseStack pose, int x1, int y1, int x2, int y2, int argb) {
            CALLS.add(new Object[]{"fill", pose, x1, y1, x2, y2, argb});
        }

        public static void drawString(FakePoseStack pose, FakeFont font, String text,
                                      int x, int y, int argb) {
            CALLS.add(new Object[]{"drawString", pose, font, text, x, y, argb});
        }

        public static void enableScissor(int x1, int y1, int x2, int y2) {
            CALLS.add(new Object[]{"enableScissor", x1, y1, x2, y2});
        }

        public static void disableScissor() {
            CALLS.add(new Object[]{"disableScissor"});
        }
    }

    /** 1.16.5：没有 enableScissor/disableScissor。 */
    public static final class FakeLegacyGuiNoScissor {
        static final List<Object[]> CALLS = new ArrayList<>();

        public static void fill(FakePoseStack pose, int x1, int y1, int x2, int y2, int argb) {
            CALLS.add(new Object[]{"fill", pose, x1, y1, x2, y2, argb});
        }

        public static void drawString(FakePoseStack pose, FakeFont font, String text,
                                      int x, int y, int argb) {
            CALLS.add(new Object[]{"drawString", pose, font, text, x, y, argb});
        }
    }

    /** 1.13–1.15：fill 只有五参（没有绘制栈），本后端必须拒绝而不是错位调用。 */
    public static final class FakeOldGui {
        public static void fill(int x1, int y1, int x2, int y2, int argb) {
        }

        public static void drawString(FakePoseStack pose, FakeFont font, String text,
                                      int x, int y, int argb) {
        }
    }

    /**
     * 1.16.5 的真实形态：{@code drawString} 与 {@code drawCenteredString} 签名完全相同
     * （真机：drawCenteredString=a、drawString=b），形状无法区分，只能靠映射名。
     */
    public static final class FakeGuiMappedNames {
        static final List<String> CALLS = new ArrayList<>();

        public static void fill(FakePoseStack pose, int x1, int y1, int x2, int y2, int argb) {
            CALLS.add("fill");
        }

        public static void drawStringMapped(FakePoseStack pose, FakeFont font, String text,
                                            int x, int y, int argb) {
            CALLS.add("mapped-drawString");
        }

        public static void drawCentered(FakePoseStack pose, FakeFont font, String text,
                                        int x, int y, int argb) {
            CALLS.add("centered");
        }
    }

    /** 映射表未收录 drawString：形状兜底应仍能选中唯一的同形状方法。 */
    public static final class FakeGuiUnmappedText {
        static final List<String> CALLS = new ArrayList<>();

        public static void fill(FakePoseStack pose, int x1, int y1, int x2, int y2, int argb) {
            CALLS.add("fill");
        }

        public static void textFallback(FakePoseStack pose, FakeFont font, String text,
                                        int x, int y, int argb) {
            CALLS.add("fallback-text");
        }
    }


    /** 带矩阵操作的绘制栈：1.16.5 的 {@code dfm}（pushPose/popPose/scale 全是单字母）。 */
    public static final class MatrixPoseStack {
        static final List<Object[]> CALLS = new ArrayList<>();

        public void pushPose() {
            CALLS.add(new Object[]{"push"});
        }

        public void popPose() {
            CALLS.add(new Object[]{"pop"});
        }

        public void scale(float x, float y, float z) {
            CALLS.add(new Object[]{"scale", x, y, z});
        }
    }

    /** 绑定到带矩阵绘制栈的 Gui：用于验证字号缩放与度量同源。 */
    public static final class FakeLegacyGuiScaled {
        static final List<Object[]> CALLS = new ArrayList<>();

        public static void fill(MatrixPoseStack pose, int x1, int y1, int x2, int y2, int argb) {
            CALLS.add(new Object[]{"fill", pose, x1, y1, x2, y2, argb});
        }

        public static void drawString(MatrixPoseStack pose, FakeFont font, String text,
                                      int x, int y, int argb) {
            CALLS.add(new Object[]{"drawString", pose, font, text, x, y, argb});
        }
    }

    /** 最小 Window 替身：物理 854x480、GUI 缩放 2 → 逻辑 427x240（真机同款）。 */
    public static final class FakeWindow {
        public int getWidth() {
            return 854;
        }

        public int getHeight() {
            return 480;
        }

        public int getGuiScaledWidth() {
            return 427;
        }

        public int getGuiScaledHeight() {
            return 240;
        }
    }

    /** 只为让 ensureFont 拿到实例；{@code getWindow} 供尺寸与裁剪换算使用。 */
    public static final class FakeMinecraft {
        public FakeWindow getWindow() {
            return new FakeWindow();
        }
    }

    /** 没有 window 的变体：裁剪换算拿不到物理高度，应安全跳过。 */
    public static final class FakeMinecraftNoWindow {
    }

    private FakePoseStack pose;
    private FakeFont font;

    @BeforeEach
    void reset() {
        FakeLegacyGui.CALLS.clear();
        FakeLegacyGuiNoScissor.CALLS.clear();
        FakeGuiMappedNames.CALLS.clear();
        FakeGuiUnmappedText.CALLS.clear();
        com.mojang.blaze3d.systems.RenderSystem.reset();
        MatrixPoseStack.CALLS.clear();
        FakeLegacyGuiScaled.CALLS.clear();
        pose = new FakePoseStack();
        font = new FakeFont();
    }

    private ExtractorRenderer bind(Class<?> guiType) {
        return bind(guiType, new LegacyMapping(false));
    }

    private ExtractorRenderer bind(Class<?> guiType, Mapping mapping) {
        return ExtractorRenderer.bindLegacy(guiType, mapping,
                FakeMinecraft::new, () -> font);
    }

    @Test
    void fullLegacyShapeBindsAsGuiStack() {
        ExtractorRenderer renderer = bind(FakeLegacyGui.class);
        assertNotNull(renderer, "静态 fill(PoseStack,int×5) + drawString(PoseStack,Font,...) 必须能绑定");
        assertEquals("gui-stack", renderer.backendName());
    }

    @Test
    void rectReachesStaticFillWithFramePoseAsFirstArgument() {
        ExtractorRenderer renderer = bind(FakeLegacyGui.class);
        assertNotNull(renderer);

        renderer.setFrame(pose);
        renderer.beginFrame();
        renderer.rect(10f, 20f, 30f, 40f, Color.WHITE);

        assertEquals(1, FakeLegacyGui.CALLS.size(), "rect 应恰好落到一次 fill: " + describe());
        Object[] call = FakeLegacyGui.CALLS.get(0);
        assertEquals("fill", call[0]);
        assertSame(pose, call[1], "fill 的首参必须是 setFrame 传入的 PoseStack");
        assertEquals(10, call[2]);
        assertEquals(20, call[3]);
        assertEquals(40, call[4]);
        assertEquals(60, call[5]);
        assertEquals(Color.WHITE.argb, call[6]);
    }

    @Test
    void textReachesStaticDrawStringInLegacyArgumentOrder() {
        ExtractorRenderer renderer = bind(FakeLegacyGui.class);
        assertNotNull(renderer);

        renderer.setFrame(pose);
        renderer.beginFrame();
        renderer.text("hi", 5f, 7f, 9f, Color.WHITE);

        assertEquals(1, FakeLegacyGui.CALLS.size(), "text 应恰好落到一次 drawString: " + describe());
        Object[] call = FakeLegacyGui.CALLS.get(0);
        assertEquals("drawString", call[0]);
        assertSame(pose, call[1]);
        assertSame(font, call[2]);
        assertEquals("hi", call[3]);
        assertEquals(5, call[4]);
        assertEquals(7, call[5]);
        assertEquals(Color.WHITE.argb, call[6]);
    }

    @Test
    void clipsUseStaticScissorMethods() {
        ExtractorRenderer renderer = bind(FakeLegacyGui.class);
        assertNotNull(renderer);

        renderer.setFrame(pose);
        renderer.beginFrame();
        renderer.pushClip(1f, 2f, 3f, 4f);
        renderer.popClip();

        assertEquals(2, FakeLegacyGui.CALLS.size(), "push/pop 应各触发一次: " + describe());
        Object[] enable = FakeLegacyGui.CALLS.get(0);
        assertEquals("enableScissor", enable[0]);
        assertEquals(1, enable[1]);
        assertEquals(2, enable[2]);
        assertEquals(4, enable[3]);
        assertEquals(6, enable[4]);
        Object[] disable = FakeLegacyGui.CALLS.get(1);
        assertEquals("disableScissor", disable[0]);
    }

    /**
     * 1.16–1.18：{@code GuiComponent} 没有裁剪原语，必须回退到 {@code RenderSystem}。
     *
     * <p>它的参数是**物理像素**、y 从帧缓冲底部算。按 GUI 逻辑坐标直传会把内容整块裁掉
     * （1.16.5 真机：面板只剩标题栏），所以这里逐项钉住换算。
     */
    @Test
    void clipsFallBackToRenderSystemInPhysicalPixels() {
        ExtractorRenderer renderer = bind(FakeLegacyGuiNoScissor.class);
        assertNotNull(renderer, "1.16–1.18 必须能绑定并借用 RenderSystem 的裁剪");

        com.mojang.blaze3d.systems.RenderSystem.reset();
        renderer.setFrame(pose);
        renderer.beginFrame();
        assertEquals(427, renderer.width());
        assertEquals(240, renderer.height());

        renderer.pushClip(10f, 20f, 100f, 50f);

        // guiScale = 854/427 = 2；矩形 (10,20)-(110,70) → 物理 (20, 480-140=340) 200x100。
        assertNotNull(com.mojang.blaze3d.systems.RenderSystem.lastScissor,
                "pushClip 必须走到 RenderSystem.enableScissor");
        int[] scissor = com.mojang.blaze3d.systems.RenderSystem.lastScissor;
        assertEquals(20, scissor[0], "x 要乘 GUI 缩放");
        assertEquals(340, scissor[1], "y 要从帧缓冲底部翻上来");
        assertEquals(200, scissor[2], "宽按缩放换算");
        assertEquals(100, scissor[3], "高按缩放换算");

        renderer.popClip();
        assertEquals(1, com.mojang.blaze3d.systems.RenderSystem.disables, "栈空必须关闭裁剪");
    }

    /** 嵌套裁剪必须取交集：GL 只有一个 scissor 矩形，子矩形不能超出父矩形。 */
    @Test
    void nestedClipsIntersectBeforeReachingTheDriver() {
        ExtractorRenderer renderer = bind(FakeLegacyGuiNoScissor.class);
        assertNotNull(renderer);

        com.mojang.blaze3d.systems.RenderSystem.reset();
        renderer.setFrame(pose);
        renderer.beginFrame();
        renderer.pushClip(10f, 10f, 100f, 100f);
        renderer.pushClip(60f, 60f, 100f, 100f);

        int[] scissor = com.mojang.blaze3d.systems.RenderSystem.lastScissor;
        assertNotNull(scissor);
        // 交集 (60,60)-(110,110) → 物理 (120, 480-220=260) 100x100。
        assertEquals(120, scissor[0]);
        assertEquals(260, scissor[1]);
        assertEquals(100, scissor[2]);
        assertEquals(100, scissor[3]);
    }

    /** 拿不到窗口物理高度时不得下发裁剪（宁可溢出，也不能整块裁掉）。 */
    @Test
    void clipIsSkippedWhenPhysicalHeightIsUnknown() {
        ExtractorRenderer renderer = ExtractorRenderer.bindLegacy(FakeLegacyGuiNoScissor.class,
                new LegacyMapping(false), FakeMinecraftNoWindow::new, () -> font);
        assertNotNull(renderer);

        com.mojang.blaze3d.systems.RenderSystem.reset();
        renderer.setFrame(pose);
        renderer.beginFrame();
        assertDoesNotThrow(() -> renderer.pushClip(1f, 2f, 3f, 4f));
        assertEquals(0, com.mojang.blaze3d.systems.RenderSystem.enables,
                "物理高度未知时不能猜坐标，跳过裁剪");
    }

    @Test
    void textPrefersTheMappedDrawStringOverTheSameShapeOverload() {
        ExtractorRenderer renderer = bind(FakeGuiMappedNames.class, new LegacyMapping(true));
        assertNotNull(renderer, "映射名与形状都应可绑定");

        renderer.setFrame(pose);
        renderer.beginFrame();
        renderer.text("hi", 5f, 7f, 9f, Color.WHITE);

        assertEquals(Collections.singletonList("mapped-drawString"), FakeGuiMappedNames.CALLS,
                "两个签名相同的文本入口并存时，必须选映射表指名的 drawString，而不是形状先遇到的居中版");
    }

    @Test
    void unmappedDrawStringFallsBackToSingleSameShapeMethod() {
        ExtractorRenderer renderer = bind(FakeGuiUnmappedText.class, new LegacyMapping(false));
        assertNotNull(renderer, "映射表没有 drawString 时必须按形状兜底绑定");

        renderer.setFrame(pose);
        renderer.beginFrame();
        renderer.text("hi", 5f, 7f, 9f, Color.WHITE);

        assertEquals(Collections.singletonList("fallback-text"), FakeGuiUnmappedText.CALLS,
                "映射名缺失时兜底选中唯一的同形状文本方法");
    }

    @Test
    void fiveArgumentFillIsRejected() {
        assertNull(bind(FakeOldGui.class),
                "只有 fill(int×5) 的 1.13–1.15 形态不能被当成静态绘制栈后端");
    }


    /**
     * 字号小于原生 9px 时必须走矩阵缩放：push → scale(f,f,1) → drawString(x/f, y/f) → pop。
     *
     * <p>1.16.5 实测教训：不缩放绘制却按 size/9 缩放度量，布局会把标签排到彼此身上。
     */
    @Test
    void scaledTextPushesScalesDrawsAndPops() {
        ExtractorRenderer renderer = bind(FakeLegacyGuiScaled.class);
        assertNotNull(renderer);

        MatrixPoseStack stack = new MatrixPoseStack();
        renderer.setFrame(stack);
        renderer.beginFrame();
        renderer.text("hi", 9f, 18f, 4.5f, Color.WHITE);

        assertEquals(3, MatrixPoseStack.CALLS.size(),
                "缩放绘制必须配平 push/scale/pop: " + MatrixPoseStack.CALLS.size());
        assertEquals("push", MatrixPoseStack.CALLS.get(0)[0]);
        Object[] scale = MatrixPoseStack.CALLS.get(1);
        assertEquals("scale", scale[0]);
        assertEquals(0.5f, (Float) scale[1], 1e-4f);
        assertEquals(1.0f, (Float) scale[3], 1e-4f);
        assertEquals("pop", MatrixPoseStack.CALLS.get(2)[0]);

        assertEquals(1, FakeLegacyGuiScaled.CALLS.size(), "缩放分支只画一次文本");
        Object[] draw = FakeLegacyGuiScaled.CALLS.get(0);
        assertEquals("drawString", draw[0]);
        assertSame(stack, draw[1], "首参仍是本帧的绘制栈");
        assertEquals(18, draw[4], "x 必须除以 factor 后再绘制");
        assertEquals(36, draw[5], "y 必须除以 factor 后再绘制");
    }

    /** 度量与绘制必须同源：能缩放时 textWidth 乘 factor，textHeight 也乘 factor。 */
    @Test
    void textWidthAndHeightFollowTheSameScaleFactor() {
        ExtractorRenderer renderer = bind(FakeLegacyGuiScaled.class);
        assertNotNull(renderer);

        MatrixPoseStack stack = new MatrixPoseStack();
        renderer.setFrame(stack);
        renderer.beginFrame();

        // FakeFont.width("hi") = 12；size 4.5 → factor 0.5。
        assertEquals(6f, renderer.textWidth("hi", 4.5f), 1e-3f);
        assertEquals(4.5f, renderer.textHeight(4.5f), 1e-3f);
    }

    /** 字号等于原生行高时不碰矩阵（避免无谓的 push/scale 与浮点误差）。 */
    @Test
    void nativeSizeTextDoesNotTouchTheMatrix() {
        ExtractorRenderer renderer = bind(FakeLegacyGuiScaled.class);
        assertNotNull(renderer);

        MatrixPoseStack stack = new MatrixPoseStack();
        renderer.setFrame(stack);
        renderer.beginFrame();
        renderer.text("hi", 5f, 7f, 9f, Color.WHITE);

        assertEquals(0, MatrixPoseStack.CALLS.size(), "factor=1 时不应操作矩阵栈");
        assertEquals(1, FakeLegacyGuiScaled.CALLS.size());
        assertEquals(5, FakeLegacyGuiScaled.CALLS.get(0)[4]);
    }

    /** 没有矩阵句柄时，度量与绘制都不缩放（同源降级，不能一边缩一边不缩）。 */
    @Test
    void withoutMatrixHandlesNeitherDrawNorMetricsScale() {
        ExtractorRenderer renderer = bind(FakeLegacyGuiNoScissor.class);
        assertNotNull(renderer);

        renderer.setFrame(pose);
        renderer.beginFrame();
        renderer.text("hi", 5f, 7f, 4.5f, Color.WHITE);

        assertEquals(12f, renderer.textWidth("hi", 4.5f), 1e-3f, "不能缩放时度量保持原生宽度");
        assertEquals(9f, renderer.textHeight(4.5f), 1e-3f, "不能缩放时行高保持原生值");
        assertEquals(5, FakeLegacyGuiNoScissor.CALLS.get(0)[4], "坐标不得除以 factor");
    }

    private static String describe() {
        List<Object[]> calls = FakeLegacyGui.CALLS.isEmpty()
                ? FakeLegacyGuiNoScissor.CALLS : FakeLegacyGui.CALLS;
        StringBuilder out = new StringBuilder();
        for (Object[] call : calls) {
            out.append(java.util.Arrays.toString(call)).append(' ');
        }
        return out.toString();
    }

    /**
     * 映射 stub：字体按真实类名命中，其余回退规范名（无描述符，不触发返回值核对）。
     *
     * <p>{@code mapDrawString=true} 时把 {@code GUI_COMPONENT.drawString} 映射为
     * {@code drawStringMapped}；{@code false} 时保持规范名（模拟映射表缺项）。
     * 两个入口都实现：非 {@code ObfuscatedMapping} 的实现走 {@link #methodName}，
     * 而生产代码对混淆表走 {@link #methodNameCandidates}。
     */
    private static final class LegacyMapping implements Mapping {
        private final boolean mapDrawString;

        LegacyMapping(boolean mapDrawString) {
            this.mapDrawString = mapDrawString;
        }

        @Override
        public String describe() {
            return "legacy-gui-stack-test";
        }

        @Override
        public String className(ClassType type) {
            if (type == ClassType.FONT) {
                return FakeFont.class.getName();
            }
            return type.canonicalName();
        }

        @Override
        public List<String> classNameCandidates(ClassType type) {
            if (type == ClassType.FONT) {
                return Collections.singletonList(FakeFont.class.getName());
            }
            if (type == ClassType.GUI_COMPONENT) {
                return Collections.singletonList(FakeGuiMappedNames.class.getName());
            }
            return Mapping.super.classNameCandidates(type);
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            if (mapDrawString && owner == ClassType.GUI_COMPONENT
                    && "drawString".equals(canonicalName)) {
                return "drawStringMapped";
            }
            return canonicalName;
        }

        @Override
        public List<String> methodNameCandidates(ClassType owner, String canonicalName,
                                                 String descriptor) {
            if (mapDrawString && owner == ClassType.GUI_COMPONENT
                    && "drawString".equals(canonicalName)) {
                return Collections.singletonList("drawStringMapped");
            }
            return Mapping.super.methodNameCandidates(owner, canonicalName, descriptor);
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }
}
