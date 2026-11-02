package org.dristmine.smartRejoin;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrozenProtocolTest {
    @Test void positionRoundTripsAcrossTheWireFormatChange() {
        for (int version = 765; version <= 775; version++) {
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
