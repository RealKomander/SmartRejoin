package org.dristmine.smartRejoin;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

/** Small wire adapter for packets not exposed by Velocity's public API. */
final class FrozenProtocol {
    private static final Properties VERSIONS = new Properties();
    static {
        try (var in = FrozenProtocol.class.getResourceAsStream("/freeze-protocols.properties")) {
            if (in == null) throw new IOException("Missing freeze protocol table");
            VERSIONS.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final int abilities, position, ticking, keepalive, gamestate, configuration;
    final int move, look, moveLook, command, signedCommand, chat, keepaliveReply;
    final boolean newPosition;

    private FrozenProtocol(int[] ids) {
        abilities = ids[0]; position = ids[1]; ticking = ids[2]; keepalive = ids[3];
        gamestate = ids[4]; configuration = ids[5]; move = ids[6]; look = ids[7];
        moveLook = ids[8]; command = ids[9]; signedCommand = ids[10]; chat = ids[11];
        keepaliveReply = ids[12]; newPosition = ids[13] == 1;
    }

    static FrozenProtocol forVersion(int version) {
        String row = VERSIONS.getProperty(Integer.toString(version));
        return row == null ? null : new FrozenProtocol(Arrays.stream(row.split(",")).mapToInt(Integer::parseInt).toArray());
    }

    static int varInt(ByteBuf in) {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte next = in.readByte();
            result |= (next & 127) << shift;
            if ((next & 128) == 0) return result;
        }
        throw new IllegalArgumentException("Oversized VarInt");
    }

    static void varInt(ByteBuf out, int value) {
        do {
            int next = value & 127;
            value >>>= 7;
            out.writeByte(value == 0 ? next : next | 128);
        } while (value != 0);
    }

    static String string(ByteBuf in) {
        int length = varInt(in);
        if (length < 0 || length > 32767 || length > in.readableBytes()) throw new IllegalArgumentException("Invalid string");
        return in.readCharSequence(length, StandardCharsets.UTF_8).toString();
    }

    static ByteBuf packet(int id) {
        ByteBuf out = Unpooled.buffer();
        varInt(out, id);
        return out;
    }

    ByteBuf ticking(boolean frozen) {
        return packet(ticking).writeFloat(20).writeBoolean(frozen);
    }

    ByteBuf abilities() {
        // Invulnerable, flying, allowed to fly; both movement speeds zero.
        return packet(abilities).writeByte(7).writeFloat(0).writeFloat(0);
    }

    ByteBuf spectator() {
        return packet(gamestate).writeByte(3).writeFloat(3);
    }

    ByteBuf keepalive(long id) {
        return packet(keepalive).writeLong(id);
    }

    ByteBuf position(Pose pose, int teleport) {
        ByteBuf out = packet(position);
        if (newPosition) varInt(out, teleport);
        out.writeDouble(pose.x).writeDouble(pose.y).writeDouble(pose.z);
        if (newPosition) out.writeDouble(0).writeDouble(0).writeDouble(0);
        out.writeFloat(pose.yaw).writeFloat(pose.pitch);
        if (newPosition) out.writeInt(0);
        else {
            out.writeByte(0);
            varInt(out, teleport);
        }
        return out;
    }

    static final class Pose {
        double x, y, z;
        float yaw, pitch;
        boolean known;

        void readMovement(int id, ByteBuf in, FrozenProtocol protocol) {
            if (id == protocol.move || id == protocol.moveLook) {
                double nx = in.readDouble(), ny = in.readDouble(), nz = in.readDouble();
                if (!Double.isFinite(nx) || !Double.isFinite(ny) || !Double.isFinite(nz)) return;
                x = nx; y = ny; z = nz; known = true;
            }
            if (id == protocol.look || id == protocol.moveLook) {
                yaw = in.readFloat(); pitch = in.readFloat();
            }
        }

        void readTeleport(ByteBuf in, FrozenProtocol protocol) {
            if (protocol.newPosition) varInt(in);
            double nx = in.readDouble(), ny = in.readDouble(), nz = in.readDouble();
            if (protocol.newPosition) in.skipBytes(24);
            float newYaw = in.readFloat(), newPitch = in.readFloat();
            int flags = protocol.newPosition ? in.readInt() : in.readUnsignedByte();
            x = (flags & 1) != 0 ? x + nx : nx;
            y = (flags & 2) != 0 ? y + ny : ny;
            z = (flags & 4) != 0 ? z + nz : nz;
            yaw = (flags & 8) != 0 ? yaw + newYaw : newYaw;
            pitch = (flags & 16) != 0 ? pitch + newPitch : newPitch;
            known = true;
        }
    }
}
