package dev.nocturne.client.action;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 内置契约的形状与校验规则。 */
class ActionContractTest {

    @Test
    void bundledContractDeclaresAllSemanticActions() {
        ActionContract contract = ActionContract.load();
        List<String> ids = new ArrayList<String>();
        for (ActionContract.ActionSpec action : contract.actions()) {
            ids.add(action.id());
        }

        assertEquals(Arrays.asList("attack", "place", "useItem", "setRotation",
                "sendPacket", "click", "swing", "swingPacket", "selectSlot", "clickSlot",
                "closeContainer", "sendChat", "legacyChat", "sendCommand", "clickUse", "setSprinting",
                "pick", "setMotion", "setJumping", "sendRotation", "sendPositionRotation",
                "sendGround", "sendPlayerCommand", "readState"), ids);
        assertEquals("MULTI_PLAYER_GAME_MODE",
                contract.get("attack").candidates().get(0).owner());
        assertEquals("gameMode", contract.get("attack").receiver());
        assertEquals("CLIENT_PACKET_LISTENER",
                contract.get("sendPacket").candidates().get(0).owner());
        assertEquals(2, contract.get("attack").candidates().get(0).parameters().size());
        assertEquals("empty", contract.get("readState").degrade());
        assertEquals(2, contract.get("useItem").candidates().size());
        assertEquals(Arrays.asList("player", "level", "hand"),
                contract.get("useItem").candidates().get(0).arguments());
        assertEquals(Arrays.asList("player", "hand", "hitResult"),
                contract.get("place").candidates().get(1).arguments());
        assertEquals("swingFactory", contract.get("swingPacket").operation());
        assertEquals(Arrays.asList("containerId", "slot", "button", "mode", "player"),
                contract.get("clickSlot").candidates().get(0).arguments());
        assertEquals(Arrays.asList("distance", "partialTicks", "fluid"),
                contract.get("pick").candidates().get(0).arguments());
        assertEquals("moveRotFactory", contract.get("sendRotation").operation());
    }

    @Test
    void duplicateActionIdsAreRejected() {
        String json = "{\"version\":1,\"actions\":["
                + "{\"id\":\"attack\",\"signature\":\"x\",\"receiver\":\"gameMode\","
                + "\"operation\":\"rotation\",\"degrade\":\"disable\",\"candidates\":[]},"
                + "{\"id\":\"attack\",\"signature\":\"y\",\"receiver\":\"gameMode\","
                + "\"operation\":\"rotation\",\"degrade\":\"disable\",\"candidates\":[]}]}";

        assertThrows(IllegalStateException.class,
                () -> ActionContract.parse(new StringReader(json)));
    }
}
