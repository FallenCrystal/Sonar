/*
 * Copyright (C) 2025 Sonar Contributors
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

package xyz.jonesdev.sonar.common.fallback.protocol.packets.play;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacket;

@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public final class SetPlayerPositionPacket implements FallbackPacket {
  private double x, y, z;
  private boolean onGround, horizontalCollision;

  @Override
  public void encode(final ByteBuf byteBuf, final ProtocolVersion protocolVersion) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void decode(final @NotNull ByteBuf byteBuf, final @NotNull ProtocolVersion protocolVersion) throws Exception {
    x = byteBuf.readDouble();
    y = byteBuf.readDouble();
    if (protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_8)) {
      // 1.7.2-1.7.10 send the minimum bounding box Y coordinate
      byteBuf.readDouble();
    }
    z = byteBuf.readDouble();
    if (protocolVersion.greaterThan(ProtocolVersion.MINECRAFT_1_21_2)) {
      short flag = byteBuf.readUnsignedByte();
      onGround = (flag & 1) != 0;
      horizontalCollision = (flag & 2) != 0;
    } else {
      onGround = byteBuf.readBoolean();
    }
  }

  @Override
  public int expectedMaxLength(final @NotNull ProtocolVersion protocolVersion) {
    return protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_8) ? 33 : 25;
  }

  @Override
  public int expectedMinLength(final ProtocolVersion protocolVersion) {
    return 25;
  }
}
