package dev.nocturne.client.game;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.mapping.ClassType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥接层的"跨版本入口"回归：当前界面、本地播报、计分板、诊断日志去重。
 *
 * <p>这四项此前在 26.3/1.8.9 上同时失效，表现是「模块开着一动不动」而不是报错：
 * 容器判断恒假、播报静默丢弃、侧边栏空白、日志每秒刷 "no overload"。
 */
class GameBridgeCompatibilityTest {

    private NocturneClient client;
    private GameBridge bridge;

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
        client = NocturneClient.boot(null);
        bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));
        client.setGameBridge(bridge);
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @Test
    void currentScreenFallsBackToGuiScreen() {
        FakeGui gui = new FakeGui();
        Object screen = new Object();
        gui.screen = screen;
        FakeMinecraft.instance.gui = gui;

        // FakeMinecraft 没有 currentScreen/screen 字段（= 26.x 的形态），必须回退到 Gui.screen()。
        assertSame(screen, bridge.currentScreen());
    }

    @Test
    void currentScreenIsNullWithoutGui() {
        assertNull(bridge.currentScreen());
    }

    @Test
    void localMessageUsesDisplayClientMessageWhenPresent() {
        FakeGui gui = new FakeGui();
        gui.displayClientMessageSupported = true;
        FakeMinecraft.instance.gui = gui;

        assertTrue(bridge.localMessage("hello"));
        assertNotNull(gui.displayed);
    }

    @Test
    void localMessageFallsBackToChatAddMessage() {
        FakeGui gui = new FakeGui();
        FakeChat chat = new FakeChat();
        gui.chat = chat;
        FakeMinecraft.instance.gui = gui;

        assertTrue(bridge.localMessage("hello"));
        assertEquals(1, chat.messages);
    }

    @Test
    void localMessageFallsBackToConstructibleTextComponent() {
        // 1.8.9 形态：映射表没有 Component#literal，但有可构造的 ChatComponentText。
        client.setGameBridge(new GameBridge(null,
                new TestMapping(FakeMinecraft.class, FakeComponentWithoutLiteral.class,
                        FakeTextComponent.class)));
        bridge = client.gameBridge();
        FakeGui gui = new FakeGui();
        FakeChat chat = new FakeChat();
        gui.chat = chat;
        FakeMinecraft.instance.gui = gui;

        assertTrue(bridge.localMessage("hello"));
        assertEquals(1, chat.messages);
        assertTrue(chat.last instanceof FakeTextComponent,
                "应回退到可构造的文本组件（1.8.9 ChatComponentText）");
    }

    @Test
    void localMessageFallsBackToEmptyAppend() {
        // 1.19+/1.20.1 形态：literal 缺失且没有可构造实现时，用 Component.empty().append(text)。
        client.setGameBridge(new GameBridge(null,
                new TestMapping(FakeMinecraft.class, FakeComponentWithoutLiteral.class, null)));
        bridge = client.gameBridge();
        FakeGui gui = new FakeGui();
        FakeChat chat = new FakeChat();
        gui.chat = chat;
        FakeMinecraft.instance.gui = gui;

        assertTrue(bridge.localMessage("hello"));
        assertEquals("hello", String.valueOf(chat.last));
    }

    @Test
    void localMessageUsesHudChatOnModernVersions() {
        // 26.x 形态：Minecraft.gui 是 Hud，聊天入口是 Hud.chat / getChat() →
        // ChatComponent.addClientSystemMessage；Gui 上的三个旧入口全部 absent。
        FakeHud263 hud = new FakeHud263();
        FakeChatComponent263 chat = new FakeChatComponent263();
        hud.chat = chat;
        FakeMinecraft.instance.gui = hud;
        TestMapping mapping = new TestMapping(FakeMinecraft.class)
                .hudAndChat(FakeHud263.class, FakeChatComponent263.class)
                .absent(new java.util.HashSet<String>(java.util.Arrays.asList(
                        "HUD#displayClientMessage", "HUD#getChat", "HUD#getChatGUI")));
        client.setGameBridge(new GameBridge(null, mapping));
        bridge = client.gameBridge();

        assertTrue(bridge.localMessage("smoke263"));
        assertEquals(1, chat.messages);
        assertEquals("smoke263", String.valueOf(chat.last));
    }

    @Test
    void localMessageDoesNotRetryAbsentGuiEntries() {
        FakeHud263 hud = new FakeHud263();
        FakeChatComponent263 chat = new FakeChatComponent263();
        hud.chat = chat;
        FakeMinecraft.instance.gui = hud;
        TestMapping mapping = new TestMapping(FakeMinecraft.class)
                .hudAndChat(FakeHud263.class, FakeChatComponent263.class)
                .absent(new java.util.HashSet<String>(java.util.Arrays.asList(
                        "HUD#displayClientMessage", "HUD#getChat", "HUD#getChatGUI")));
        client.setGameBridge(new GameBridge(null, mapping));
        bridge = client.gameBridge();

        assertTrue(bridge.localMessage("one"));
        assertTrue(bridge.localMessage("two"));
        assertEquals(2, chat.messages);
        assertEquals(0, hud.getChatCalls, "26.x 不应再走 Gui.getChat/getChatGUI 入口");
    }

    @Test
    void localMessageReportsFailureWhenNoEntryExists() {
        FakeMinecraft.instance.gui = new FakeGui();
        assertFalse(bridge.localMessage("hello"));
    }

    @Test
    void scoreboardReadsModernListPlayerScores() {
        FakeMinecraft.instance.level = FakeScoreboard.levelWith("Objective", "Steve", 42);

        List<String> lines = bridge.scoreboardLines();

        assertEquals(2, lines.size());
        assertEquals("Objective", lines.get(0));
        assertTrue(lines.get(1).contains("Steve"));
        assertTrue(lines.get(1).contains("42"));
    }

    @Test
    void repeatedDiagnosticsArePrintedOnce() {
        Object target = new Object();
        bridge.callMapped(target, ClassType.ENTITY, "totallyMissingMethod");
        int afterFirst = reportedMessageCount();
        bridge.callMapped(target, ClassType.ENTITY, "totallyMissingMethod");
        assertEquals(afterFirst, reportedMessageCount(),
                "同一条映射缺失诊断只应记录一次");
    }

    @SuppressWarnings("unchecked")
    private static int reportedMessageCount() {
        try {
            Field field = GameBridge.class.getDeclaredField("REPORTED_MESSAGES");
            field.setAccessible(true);
            return ((Set<String>) field.get(null)).size();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** 替身聊天框：记录 {@code addMessage} 次数。 */
    public static final class FakeChat {
        int messages;
        Object last;

        public void addMessage(Object component) {
            messages++;
            last = component;
        }
    }
}
