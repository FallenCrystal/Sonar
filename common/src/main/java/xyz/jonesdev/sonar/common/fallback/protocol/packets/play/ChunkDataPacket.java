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
import lombok.*;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacket;
import xyz.jonesdev.sonar.common.util.ProtocolUtil;

@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public final class ChunkDataPacket implements FallbackPacket {
  private int sectionX, sectionZ;
  private int totalHeight; // For 1.18+ world

  public ChunkDataPacket(final int sectionX, final int sectionZ) {
    this(sectionX, sectionZ, 380);
  }

  @Override
  public void encode(final @NotNull ByteBuf byteBuf, final @NotNull ProtocolVersion protocolVersion) throws Exception {
    byteBuf.writeInt(sectionX);
    byteBuf.writeInt(sectionZ);

    if (protocolVersion.greaterThanOrEquals(ProtocolVersion.MINECRAFT_1_17)) {
      if (protocolVersion.lessThanOrEquals(ProtocolVersion.MINECRAFT_1_17_1)) {
        ProtocolUtil.writeVarInt(byteBuf, 0); // mask
      }
    } else {
      byteBuf.writeBoolean(true); // full chunk

      if (protocolVersion.inBetween(ProtocolVersion.MINECRAFT_1_16, ProtocolVersion.MINECRAFT_1_16_1)) {
        byteBuf.writeBoolean(true); // ignore old data
      }

      if (protocolVersion.greaterThan(ProtocolVersion.MINECRAFT_1_8)) {
        ProtocolUtil.writeVarInt(byteBuf, 0);
      } else {
        byteBuf.writeShort(1); // fix void chunk
      }
    }

    if (protocolVersion.greaterThanOrEquals(ProtocolVersion.MINECRAFT_1_14)) {
      // Height map
      final long[] motionBlockingData = new long[protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_18) ? 36 : 37];
      final CompoundBinaryTag motionBlockingTag = CompoundBinaryTag.builder()
        .put("MOTION_BLOCKING", LongArrayBinaryTag.longArrayBinaryTag(motionBlockingData))
        .build();
      final CompoundBinaryTag rootTag = CompoundBinaryTag.builder()
        .put("root", motionBlockingTag)
        .build();

      ProtocolUtil.writeBinaryTag(byteBuf, protocolVersion, rootTag);

      if (protocolVersion.inBetween(ProtocolVersion.MINECRAFT_1_15, ProtocolVersion.MINECRAFT_1_17_1)) {
        if (protocolVersion.greaterThanOrEquals(ProtocolVersion.MINECRAFT_1_16_2)) {
          ProtocolUtil.writeVarInt(byteBuf, 1024);

          for (int i = 0; i < 1024; i++) {
            ProtocolUtil.writeVarInt(byteBuf, 1);
          }
        } else {
          for (int i = 0; i < 1024; i++) {
            byteBuf.writeInt(0);
          }
        }
      }
    }

    if (protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_8)) {
      byteBuf.writeInt(0);
      byteBuf.writeBytes(new byte[2]);
    } else if (protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_13)) {
      ProtocolUtil.writeVarInt(byteBuf, 0);
    } else if (protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_15)) {
      ProtocolUtil.writeByteArray(byteBuf, new byte[256 * 4]);
    } else if (protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_18)) {
      ProtocolUtil.writeVarInt(byteBuf, 0);
    } else {
      // Let's get to know the decoding of PalettedContainer first:
      // bits (byte) -  It will decide what Palette is assigned. 0 is always SINGULAR (Only accept 1 value).
      // Each different Palette provider will assign a different Palette based on the bits.
      // (See PalettedContainer.PaletteProvider)
      // entry - Palette#readPacket(ByteBuf). SingularPalette read 1 varint for value.
      // longArray - Bytes to PaletteStorage. Put 0 (varint) for empty long array.
      final byte[] sectionData = new byte[]{
        0, 0, // nonEmptyBlockCount. short occupies 2 bytes.
        0, 0, 0, // blockStateContainer. 0 is air – everything is air.
        0, 1, 0 // biomeContainer. 1 is biome value that id in registry.
      };
      // We can ignore the decimal of the result and add 1 if it is not divisible by 16.
      int count = (totalHeight + 15) / 16;
      ProtocolUtil.writeVarInt(byteBuf, sectionData.length * count);

      for (int i = 0; i < count; i++) {
        byteBuf.writeBytes(sectionData);
      }
    }

    if (protocolVersion.greaterThanOrEquals(ProtocolVersion.MINECRAFT_1_9_4)) {
      ProtocolUtil.writeVarInt(byteBuf, 0);
    }

    if (protocolVersion.greaterThanOrEquals(ProtocolVersion.MINECRAFT_1_21_2)) {
      for (int i = 0; i < 6; i++) {
        ProtocolUtil.writeVarInt(byteBuf, 0);
      }
    } else if (protocolVersion.greaterThanOrEquals(ProtocolVersion.MINECRAFT_1_18)) {
      final byte[] lightData = new byte[]{1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 3, -1, -1, 0, 0};

      byteBuf.ensureWritable(lightData.length);

      if (protocolVersion.greaterThanOrEquals(ProtocolVersion.MINECRAFT_1_20)) {
        // bitSet is actually read from long array
        // 0, 0, 0: initedSky, initedBlock, uninitedSky (empty array)
        // 1, 0, 0, 0, 0, 0, 3, -1, -1: unititedBlock (1 size long array)
        // 0, 0: skyNibbles, blockNibbles (empty List<long[]>)
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
