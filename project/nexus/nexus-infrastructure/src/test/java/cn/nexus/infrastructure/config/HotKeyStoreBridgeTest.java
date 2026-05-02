package cn.nexus.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HotKeyStoreBridgeTest {

    @Test
    void disabledDetectorShouldStayCold() {
        HotKeyStoreBridge bridge = bridge(false, 2, 4, 6, 30, 10);

        assertFalse(bridge.isHotKey("k"));
        assertFalse(bridge.isHotKey("k"));
        assertFalse(bridge.isHotKey("k"));

        assertEquals(0, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));
    }

    @Test
    void blankKeyShouldStayCold() {
        HotKeyStoreBridge bridge = bridge(true, 2, 4, 6, 30, 10);

        assertFalse(bridge.isHotKey(null));
        assertFalse(bridge.isHotKey(""));
        assertFalse(bridge.isHotKey("   "));

        assertEquals(0, bridge.heat(null));
        assertEquals(0, bridge.heat(""));
        assertEquals(0, bridge.heat("   "));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level(""));
    }

    @Test
    void isHotKeyShouldRecordAndExposeLevels() {
        HotKeyStoreBridge bridge = bridge(true, 2, 4, 6, 30, 10);

        assertFalse(bridge.isHotKey("k"));
        assertEquals(1, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));

        assertTrue(bridge.isHotKey("k"));
        assertEquals(2, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.LOW, bridge.level("k"));

        assertTrue(bridge.isHotKey("k"));
        assertEquals(3, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.LOW, bridge.level("k"));

        assertTrue(bridge.isHotKey("k"));
        assertEquals(4, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.MEDIUM, bridge.level("k"));

        assertTrue(bridge.isHotKey("k"));
        assertEquals(5, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.MEDIUM, bridge.level("k"));

        assertTrue(bridge.isHotKey("k"));
        assertEquals(6, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.HIGH, bridge.level("k"));
    }

    @Test
    void heatLevelResetAndRotateShouldNotRecordAccess() {
        HotKeyStoreBridge bridge = bridge(true, 2, 4, 6, 30, 10);

        assertFalse(bridge.isHotKey("k"));
        assertEquals(1, bridge.heat("k"));

        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));
        assertEquals(1, bridge.heat("k"));
        assertEquals(1, bridge.heat("k"));

        bridge.rotate();
        assertEquals(1, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));

        bridge.reset("k");
        assertEquals(0, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));

        bridge.reset("k");
        bridge.rotate();
        assertEquals(0, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));
    }

    @Test
    void rotateThroughAllSegmentsShouldExpireHeat() {
        HotKeyStoreBridge bridge = bridge(true, 2, 4, 6, 30, 10);

        assertFalse(bridge.isHotKey("k"));
        assertTrue(bridge.isHotKey("k"));
        assertEquals(2, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.LOW, bridge.level("k"));

        bridge.rotate();
        assertEquals(2, bridge.heat("k"));

        bridge.rotate();
        assertEquals(2, bridge.heat("k"));

        bridge.rotate();
        assertEquals(0, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));
        assertFalse(bridge.isHotKey("k"));
    }

    @Test
    void nonEvenWindowShouldRoundUpSegments() {
        HotKeyStoreBridge bridge = bridge(true, 2, 4, 6, 25, 10);

        assertFalse(bridge.isHotKey("k"));
        assertTrue(bridge.isHotKey("k"));
        assertEquals(2, bridge.heat("k"));

        bridge.rotate();
        assertEquals(2, bridge.heat("k"));

        bridge.rotate();
        assertEquals(2, bridge.heat("k"));

        bridge.rotate();
        assertEquals(0, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));
    }

    @Test
    void invalidWindowAndSegmentShouldUseSingleSegment() {
        HotKeyStoreBridge bridge = bridge(true, 2, 4, 6, 0, 0);

        assertFalse(bridge.isHotKey("k"));
        assertTrue(bridge.isHotKey("k"));
        assertEquals(2, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.LOW, bridge.level("k"));

        bridge.rotate();
        assertEquals(0, bridge.heat("k"));
        assertEquals(HotKeyStoreBridge.Level.NONE, bridge.level("k"));
    }

    private HotKeyStoreBridge bridge(
            boolean enabled,
            int levelLow,
            int levelMedium,
            int levelHigh,
            int windowSeconds,
            int segmentSeconds
    ) {
        HotKeyProperties properties = new HotKeyProperties();
        properties.setEnabled(enabled);
        properties.setLevelLow(levelLow);
        properties.setLevelMedium(levelMedium);
        properties.setLevelHigh(levelHigh);
        properties.setWindowSeconds(windowSeconds);
        properties.setSegmentSeconds(segmentSeconds);
        return new HotKeyStoreBridge(properties);
    }
}
