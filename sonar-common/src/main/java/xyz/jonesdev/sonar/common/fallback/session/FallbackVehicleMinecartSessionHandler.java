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

package xyz.jonesdev.sonar.common.fallback.session;

import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.api.fallback.FallbackUser;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacketDecoder;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.PaddleBoatPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.PlayerInputPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.SetPlayerRotationPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.VehicleMovePacket;

import static xyz.jonesdev.sonar.common.fallback.protocol.FallbackPreparer.*;

/**
 * Minecart vehicle check for Java 1.9+ clients.
 * <p>
 * The differences for the {@link FallbackVehicleBoatSessionHandler} check:
 * <ol>
 *   <li>
 *     The player does not send {@link PaddleBoatPacket}
 *     and {@link VehicleMovePacket} packets on the minecart.
 *     Same behavior as 1.8 and earlier clients.
 *   </li>
 *   <li>Skipped the movement check because it doesn't need to.</li>
 * </ol>
 */
public final class FallbackVehicleMinecartSessionHandler extends FallbackVehicleSessionHandler {
  public FallbackVehicleMinecartSessionHandler(@NotNull FallbackUser user,
                                               @NotNull String username,
                                               boolean forceCAPTCHA) {
    super(user, username, forceCAPTCHA);
  }

  @Override
  void markSuccess() {
    // Either send the player to the CAPTCHA or finish the verification.
    if (forceCAPTCHA || Sonar.get().getFallback().shouldPerformCaptcha()) {
      // Send the player to the CAPTCHA handler
      final var decoder = user.getPipeline().get(FallbackPacketDecoder.class);
      decoder.setListener(new FallbackCAPTCHASessionHandler(user, username));
    } else {
      // The player has passed all checks.
      finishVerification();
    }
  }

  @Override
  void handleRotation(SetPlayerRotationPacket packet) {
    checkState(Math.abs(packet.getPitch()) <= 90, "invalid rotation pitch");
    if (!isPreExceptMovement() || !isExpectMovement()) {
      // Check packet order first
      checkState(rotationPackets == inputPackets,
        "illegal packet order; r/i" + rotationPackets + "/" + inputPackets);

      // Once the player sent enough packets, go to the next stage
      final int minimumPackets = Sonar.get().getConfig().getVerification().getVehicle().getMinimumPackets();
      if (inputPackets > minimumPackets && rotationPackets > minimumPackets) {
        setPreExceptMovement(true);
        destroy();
        user.getChannel().flush();
        markSuccess();
      }
    }
  }

  // Clients don't send PaddleBoat & VehicleMovePacket packets while riding minecarts.
  // Instantly mark failure for them.

  @Override
  void handlePaddleBoat(@NotNull PaddleBoatPacket packet) {
    user.fail("invalid packet order (unexpected PaddleBoatPacket)");
  }

  @Override
  void handleVehicleMove(@NotNull VehicleMovePacket packet) {
    user.fail("invalid packet order (unexpected VehicleMovePacket)");
  }

  @Override
  void handleInput(@NotNull PlayerInputPacket packet) {
    checkState(packet.getForward() <= 0.98f, "illegal speed (f): " + packet.getForward());
    checkState(packet.getSideways() <= 0.98f, "illegal speed (s): " + packet.getSideways());

    if (packet.isJump() && packet.isUnmount()) {
      return;
    }

    checkState(inputPackets + 1 == rotationPackets,
      "illegal packet order; r/i " + rotationPackets + "/" + inputPackets);
  }

  @Override
  void destroy() {
    user.write(removeMinecartEntities);
  }

  @Override
  void spawn() {
    user.delayedWrite(spawnMinecartEntity);
    user.delayedWrite(setMinecartPassengers);
  }
}
