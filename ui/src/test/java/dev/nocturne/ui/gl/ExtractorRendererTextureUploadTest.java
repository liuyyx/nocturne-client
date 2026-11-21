package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.21.11+ 的 register(Identifier, TextureContents) 路径：注册前后都必须有真实像素。 */
class ExtractorRendererTextureUploadTest {

    public static final class Identifier {
        final String namespace;
        final String path;

        Identifier(String namespace, String path) {
            this.namespace = namespace;
            this.path = path;
        }
    }

    public static final class Identifiers {
        public static Identifier fromNamespaceAndPath(String namespace, String path) {
            return new Identifier(namespace, path);
        }
    }

    /** 以 int 数组模拟 NativeImage，记录 setPixel 实际收到的 ABGR。 */
    public static final class NativeImage {
        private final int width;
        private final int[] pixels;

        NativeImage(int width, int height) {
            this.width = width;
            pixels = new int[width * height];
        }

        public void setPixel(int x, int y, int value) {
            pixels[y * width + x] = value;
        }
    }

    public static final class DynamicTexture {
        private final NativeImage pixels;
        boolean closed;

        public DynamicTexture(String name, int width, int height, boolean calloc) {
            pixels = new NativeImage(width, height);
        }

        public NativeImage getPixels() {
            return pixels;
        }

        public void close() {
            closed = true;
        }
    }

    public static final class FailedTexture {
        static final List<FailedTexture> created = new ArrayList<FailedTexture>();
        final FailedPixels pixels = new FailedPixels();
        boolean closed;

        public FailedTexture(String name, int width, int height, boolean calloc) {
            created.add(this);
        }

        public FailedPixels getPixels() {
            return pixels;
        }

        public void close() {
            closed = true;
        }
    }

    public static final class FailedPixels {
    }

    /** register 本身就是 GPU 上传：快照必须捕获调用当下的像素，而不是之后继续修改的数组。 */
    public static final class TextureManager {
        final List<int[]> uploads = new ArrayList<int[]>();
        final List<DynamicTexture> registered = new ArrayList<DynamicTexture>();

        public void register(Identifier identifier, DynamicTexture texture) {
            uploads.add(texture.pixels.pixels.clone());
            registered.add(texture);
        }
    }

    private static void setField(ExtractorRenderer renderer, String name, Object value)
            throws Exception {
        Field field = ExtractorRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(renderer, value);
    }

    private static ExtractorRenderer registerUploadBackend(TextureManager manager) throws Exception {
        ExtractorRenderer renderer = ExtractorRenderer.bind(
                ExtractorRendererTest.FakeExtractor.class, () -> null);
        assertNotNull(renderer);
        setField(renderer, "textureSupport", true);
        setField(renderer, "registerUploads", true);
        setField(renderer, "textureManager", manager);
        setField(renderer, "newIdentifier", Identifiers.class.getMethod(
                "fromNamespaceAndPath", String.class, String.class));
        setField(renderer, "newDynamicTexture", DynamicTexture.class.getConstructor(
                String.class, int.class, int.class, boolean.class));
        setField(renderer, "getPixels", DynamicTexture.class.getMethod("getPixels"));
        setField(renderer, "setPixel", NativeImage.class.getMethod(
                "setPixel", int.class, int.class, int.class));
        setField(renderer, "registerTexture", TextureManager.class.getMethod(
                "register", Identifier.class, DynamicTexture.class));
        renderer.setFrame(new ExtractorRendererTest.FakeExtractor());
        return renderer;
    }

    @Test
    void failedPixelWriteClosesTextureWithoutPublishingId() throws Exception {
        TextureManager manager = new TextureManager();
        ExtractorRenderer renderer = registerUploadBackend(manager);
        setField(renderer, "newDynamicTexture", FailedTexture.class.getConstructor(
                String.class, int.class, int.class, boolean.class));
        setField(renderer, "getPixels", FailedTexture.class.getMethod("getPixels"));
        setField(renderer, "setPixel", null);
        setField(renderer, "applyPixels", null);
        setField(renderer, "setPixels", null);
        setField(renderer, "closeTexture", FailedTexture.class.getMethod("close"));
        setField(renderer, "registerTexture", null);

        int id = renderer.uploadTexture(0, 2, 1, new int[]{0x11223344, 0xFFEEDDCC});

        assertEquals(0, id, "像素写入失败不能返回新建的有效句柄");
        assertTrue(manager.registered.isEmpty(), "失败纹理不得注册进 TextureManager");
        Field table = ExtractorRenderer.class.getDeclaredField("textures");
        table.setAccessible(true);
        assertEquals(1, ((List<?>) table.get(renderer)).size(),
                "失败纹理不得占用句柄表槽位");
        assertEquals(1, FailedTexture.created.size());
        assertTrue(FailedTexture.created.get(0).closed,
                "未注册的 DynamicTexture 必须立即 close");
        // 失败对象已关闭；句柄表与注册表均无残留。
    }

    private static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    private static int[] abgr(int[] argb) {
        int[] converted = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            converted[i] = abgr(argb[i]);
        }
        return converted;
    }

    @Test
    void writesPixelsBeforeRegistrationAndReregistersRegionUpdates() throws Exception {
        TextureManager manager = new TextureManager();
        ExtractorRenderer renderer = registerUploadBackend(manager);
        int[] initial = {0x11223344, 0xFFEEDDCC};

        int id = renderer.uploadTexture(0, 2, 1, initial);

        assertEquals(1, id);
        assertEquals(1, manager.uploads.size());
        assertArrayEquals(abgr(initial), manager.uploads.get(0),
                "首次注册前必须写入 NativeImage，不能上传 calloc 的透明像素");

        int[] addedGlyph = {0xFF010203, 0x800A0B0C};
        int updated = renderer.uploadTextureRegion(id, 2, 1, addedGlyph, 0, 0, 1, 1);

        assertEquals(id, updated);
        assertEquals(2, manager.uploads.size(), "区域更新必须再次触发注册上传");
        assertArrayEquals(abgr(addedGlyph), manager.uploads.get(1));
        assertSame(manager.registered.get(0), manager.registered.get(1),
                "更新同一图集时不得换成另一份纹理对象");
    }
}
