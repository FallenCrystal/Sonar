/*
 * Copyright (C) 2023 Sonar Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.jonesdev.sonar.common.fallback.packets;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.ProtocolVersion;

import static xyz.jonesdev.sonar.common.fallback.protocol.ProtocolVersion.*;
import static xyz.jonesdev.sonar.common.protocol.VarIntUtil.writeVarInt;

@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public final class PositionLook implements FallbackPacket {
  private double x, y, z;
  private float yaw, pitch;
  private int teleportId;
  private boolean onGround;

  @Override
  public void decode(final ByteBuf byteBuf, final ProtocolVersion protocolVersion) {
    x = byteBuf.readDouble();
    y = byteBuf.readDouble();
    z = byteBuf.readDouble();
    yaw = byteBuf.readFloat();
    pitch = byteBuf.readFloat();
    onGround = byteBuf.readBoolean();
  }

  @Override
  public void encode(final ByteBuf byteBuf, final ProtocolVersion protocolVersion) {
    byteBuf.writeDouble(x);
    byteBuf.writeDouble(y);
    byteBuf.writeDouble(z);
    byteBuf.writeFloat(yaw);
    byteBuf.writeFloat(pitch);
    byteBuf.writeByte(0x00);

    if (protocolVersion.compareTo(MINECRAFT_1_9) >= 0) {
      writeVarInt(byteBuf, teleportId);
    }

    if (protocolVersion.compareTo(MINECRAFT_1_17) >= 0 && protocolVersion.compareTo(MINECRAFT_1_19_3) <= 0) {
      byteBuf.writeBoolean(true); // Dismount vehicle
    }
  }
}