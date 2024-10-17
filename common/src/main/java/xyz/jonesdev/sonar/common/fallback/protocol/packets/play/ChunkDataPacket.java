/*
 * Copyright (C) 2024 Sonar Contributors
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
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacket;

import static xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion.*;
import static xyz.jonesdev.sonar.common.util.ProtocolUtil.*;

@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public final class ChunkDataPacket implements FallbackPacket {
  private int sectionX, sectionZ;

  @Override
  public void encode(final @NotNull ByteBuf byteBuf, final @NotNull ProtocolVersion protocolVersion) throws Exception {
    byteBuf.writeInt(sectionX);
    byteBuf.writeInt(sectionZ);

    if (protocolVersion.greaterThanOrEquals(MINECRAFT_1_17)) {
      if (protocolVersion.lessThanOrEquals(MINECRAFT_1_17_1)) {
        writeVarInt(byteBuf, 0); // mask
      }
    } else {
      byteBuf.writeBoolean(true); // full chunk

      if (protocolVersion.inBetween(MINECRAFT_1_16, MINECRAFT_1_16_1)) {
        byteBuf.writeBoolean(true); // ignore old data
      }

      if (protocolVersion.greaterThan(MINECRAFT_1_8)) {
        writeVarInt(byteBuf, 0);
      } else {
        byteBuf.writeShort(1); // fix void chunk
      }
    }

    if (protocolVersion.greaterThanOrEquals(MINECRAFT_1_14)) {
      final long[] motionBlockingData = new long[protocolVersion.lessThan(MINECRAFT_1_18) ? 36 : 37];
      final CompoundBinaryTag motionBlockingTag = CompoundBinaryTag.builder()
        .put("MOTION_BLOCKING", LongArrayBinaryTag.longArrayBinaryTag(motionBlockingData))
        .build();
      final CompoundBinaryTag rootTag = CompoundBinaryTag.builder()
        .put("root", motionBlockingTag)
        .build();

      writeBinaryTag(byteBuf, protocolVersion, rootTag);

      if (protocolVersion.inBetween(MINECRAFT_1_15, MINECRAFT_1_17_1)) {
        if (protocolVersion.greaterThanOrEquals(MINECRAFT_1_16_2)) {
          writeVarInt(byteBuf, 1024);

          for (int i = 0; i < 1024; i++) {
            writeVarInt(byteBuf, 1);
          }
        } else {
          for (int i = 0; i < 1024; i++) {
            byteBuf.writeInt(0);
          }
        }
      }
    }

    if (protocolVersion.lessThan(MINECRAFT_1_8)) {
      byteBuf.writeInt(0);
      byteBuf.writeBytes(new byte[2]);
    } else if (protocolVersion.lessThan(MINECRAFT_1_15)) {
      writeVarInt(byteBuf, 0);
    } else if (protocolVersion.lessThan(MINECRAFT_1_18)) {
      writeByteArray(byteBuf, new byte[256 * 4]);
    } else {
      final byte[] sectionData = new byte[]{0, 0, 0, 0, 0, 0, 1, 0};
      int count = protocolVersion.greaterThanOrEquals(MINECRAFT_1_21_2_PRE5) ? 24 : 16;
      writeVarInt(byteBuf, sectionData.length * count);

      for (int i = 0; i < count; i++) {
        byteBuf.writeBytes(sectionData);
      }
    }

    if (protocolVersion.greaterThanOrEquals(MINECRAFT_1_9_4)) {
      writeVarInt(byteBuf, 0);
    }

    if (protocolVersion.greaterThanOrEquals(MINECRAFT_1_21_2_PRE5)) {
      for (int i = 0; i < 6; i++) {
        writeVarInt(byteBuf, 0);
      }
    } else if (protocolVersion.greaterThanOrEquals(MINECRAFT_1_18)) {
      final byte[] lightData = new byte[]{1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 3, -1, -1, 0, 0};

      byteBuf.ensureWritable(lightData.length);

      if (protocolVersion.greaterThanOrEquals(MINECRAFT_1_20)) {
        byteBuf.writeBytes(lightData, 1, lightData.length - 1);
      } else {
        byteBuf.writeBytes(lightData);
      }
    }
  }

  @Override
  public void decode(final ByteBuf byteBuf, final ProtocolVersion protocolVersion) {
    throw new UnsupportedOperationException();
  }
}
