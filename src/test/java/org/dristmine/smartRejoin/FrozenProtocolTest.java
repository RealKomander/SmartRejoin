package org.dristmine.smartRejoin;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrozenProtocolTest {
    @Test void positionRoundTripsAcrossTheWireFormatChange() {
        for (int version = 765; version <= 777; version++) {
            FrozenProtocol protocol = FrozenProtocol.forVersion(version);
            assertNotNull(protocol);
            FrozenProtocol.Pose pose = new FrozenProtocol.Pose();
            pose.x = 12.5; pose.y = 64; pose.z = -22.25; pose.yaw = 90; pose.pitch = 12;
            ByteBuf bytes = protocol.position(pose, 12345);
            try {
                assertEquals(protocol.position, FrozenProtocol.varInt(bytes));
                FrozenProtocol.Pose decoded = new FrozenProtocol.Pose();
                decoded.readTeleport(bytes, protocol);
                assertTrue(decoded.known);
                assertEquals(pose.x, decoded.x);
                assertEquals(pose.y, decoded.y);
                assertEquals(pose.z, decoded.z);
                assertEquals(pose.yaw, decoded.yaw);
                assertEquals(pose.pitch, decoded.pitch);
                if (!protocol.newPosition) assertEquals(12345, FrozenProtocol.varInt(bytes));
                assertFalse(bytes.isReadable());
            } finally { bytes.release(); }
        }
    }

    @Test void protocol777UsesItsOwnPacketIdsRatherThanProtocol775() {
        FrozenProtocol protocol = FrozenProtocol.forVersion(777);
        assertNotNull(protocol);
        assertEquals(0x41, protocol.abilities);
        assertEquals(0x49, protocol.position);
        assertEquals(0x82, protocol.ticking);
        assertEquals(0x2d, protocol.keepalive);
        assertEquals(0x27, protocol.gamestate);
        assertEquals(0x78, protocol.configuration);
        assertEquals(0x1e, protocol.move);
        assertEquals(0x1f, protocol.moveLook);
        assertEquals(0x20, protocol.look);
        assertEquals(0x07, protocol.command);
        assertEquals(0x08, protocol.signedCommand);
        assertEquals(0x1c, protocol.keepaliveReply);
        ByteBuf freeze = protocol.ticking(true);
        try {
            // Independently specified wire bytes: VarInt 0x82, float 20, bool true.
            byte[] actual = new byte[freeze.readableBytes()];
            freeze.readBytes(actual);
            assertArrayEquals(java.util.HexFormat.of().parseHex("820141a0000001"), actual);
        } finally { freeze.release(); }
        assertNotEquals(FrozenProtocol.forVersion(775).ticking, protocol.ticking);
        assertNotNull(FrozenProtocol.forVersion(776));
    }

    @Test void nativeFreezeAndMovementLockHaveExpectedPayloads() {
        FrozenProtocol protocol = FrozenProtocol.forVersion(765);
        ByteBuf freeze = protocol.ticking(true);
        ByteBuf abilities = protocol.abilities();
        try {
            assertEquals(110, FrozenProtocol.varInt(freeze));
            assertEquals(20f, freeze.readFloat());
            assertTrue(freeze.readBoolean());
            assertEquals(54, FrozenProtocol.varInt(abilities));
            assertEquals(7, abilities.readByte());
            assertEquals(0f, abilities.readFloat());
            assertEquals(0f, abilities.readFloat());
        } finally { freeze.release(); abilities.release(); }
        assertNull(FrozenProtocol.forVersion(764));
        assertNull(FrozenProtocol.forVersion(9999));
    }
}
