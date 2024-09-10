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
import xyz.jonesdev.sonar.api.config.SonarConfiguration;
import xyz.jonesdev.sonar.api.fallback.FallbackUser;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacketDecoder;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.PaddleBoatPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.PlayerInputPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.SetPlayerRotationPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.VehicleMovePacket;

import static xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion.MINECRAFT_1_8;
import static xyz.jonesdev.sonar.common.fallback.protocol.FallbackPreparer.*;

/**
 * Contains basic vehicle checks. Specifically, for boats.
 * <p>
 * When all the required packets sent exceed the value specified by
 * {@link SonarConfiguration.Verification.Vehicle#getMinimumPackets()}.
 * Dismounts the player from the vehicle.
 * (Although players have to go through at least x+2 ticks to meet this condition)
 * <p>
 * Strictly check the order of packets from (java) clients:
 *
 * <ol>
 * <li>{@link PaddleBoatPacket} (1.9+)</li>
 * <li>{@link SetPlayerRotationPacket}</li>
 * <li>{@link PlayerInputPacket}</li>
 * <li>{@link VehicleMovePacket} (1.9+)</li>
 * </ol>
 *
 * Geyser client order: (FYI. There is no need to check the order of packets from geyser.)
 * <ol>
 * <li>{@link VehicleMovePacket}</li>
 * <li>{@link PaddleBoatPacket}</li>
 * <li>{@link PaddleBoatPacket}</li>
 * <li>{@link PlayerInputPacket}</li>
 * </ol>
 * See more: {@link FallbackVehicleSessionHandler}
 * <p>
 * If the player's client version is (java) 1.9+.
 * Then implement minecart vehicle check.
 * Otherwise, Mark for verification finished.
 */
public final class FallbackVehicleBoatSessionHandler extends FallbackVehicleSessionHandler {
  private final boolean exemptRotation;
  private final boolean exemptPaddleBoat;

  public FallbackVehicleBoatSessionHandler(
    @NotNull FallbackUser user,
    @NotNull String username,
    boolean forceCAPTCHA
  ) {
    super(user, username, forceCAPTCHA);
    exemptRotation = user.isGeyser();
    exemptPaddleBoat = user.isGeyser() || user.getProtocolVersion().compareTo(MINECRAFT_1_8) <= 0;
  }

  @Override
  void markSuccess() {
    // Pass the player to the next best verification handler
    final var decoder = user.getPipeline().get(FallbackPacketDecoder.class);
    // Exempt minecart check if player is exempt rotation (Geyser) or paddleBoat (1.7-1.8)
    if (exemptRotation || exemptPaddleBoat) {
      // Either send the player to the CAPTCHA or finish the verification.
      if (forceCAPTCHA || Sonar.get().getFallback().shouldPerformCaptcha()) {
        // Send the player to the CAPTCHA handler
        decoder.setListener(new FallbackCAPTCHASessionHandler(user, username));
      } else {
        // The player has passed all checks
        finishVerification();
      }
    } else {
      decoder.setListener(new FallbackVehicleMinecartSessionHandler(user, username, forceCAPTCHA));
    }
  }

  @Override
  void handlePaddleBoat(@NotNull PaddleBoatPacket packet) {
    if (!exemptPaddleBoat) {
      // The first packet should be PaddleBoatPacket.
      // This method will be invoked before increase the paddlePackets.
      checkState(exemptRotation || paddlePackets == rotationPackets,
        "illegal packet order; p/r " + paddlePackets + "/" + rotationPackets);
      checkState(paddlePackets == inputPackets,
        "illegal packet order; p/i " + paddlePackets + "/" + inputPackets);
      checkState(paddlePackets == vehicleMovePackets,
        "illegal packet order; p/v " + paddlePackets + "/" + vehicleMovePackets);
    }
  }

  @Override
  void handleRotation(SetPlayerRotationPacket packet) {
    if (packet != null) {
      checkState(Math.abs(packet.getPitch()) <= 90, "invalid rotation pitch");
    }
    if (!isPreExceptMovement() || !isExpectMovement()) {
      // If the packet is not null, then check the packet order.
      if (!exemptRotation && packet != null) {
        // before
        checkState(exemptPaddleBoat || rotationPackets + 1 == paddlePackets,
          "illegal packet order; r/p " + rotationPackets + "/" + paddlePackets);
        // after
        checkState(rotationPackets == inputPackets,
          "illegal packet order; r/i " + rotationPackets + "/" + inputPackets);
        checkState(exemptPaddleBoat || rotationPackets == vehicleMovePackets,
          "illegal packet order; r/v " + rotationPackets + "/" + vehicleMovePackets);
      }

      // Once the player sent enough packets, go to the next stage
      final int minimumPackets = Sonar.get().getConfig().getVerification().getVehicle().getMinimumPackets();
      // Only required java player to send all packets.
      if (inputPackets > minimumPackets
        && (exemptRotation || rotationPackets > minimumPackets)
        && (exemptPaddleBoat || (paddlePackets > minimumPackets && vehicleMovePackets > minimumPackets))) {
        setPreExceptMovement(true);
        destroy();
        if (user.isGeyser()) {
          setExpectMovement(true);
        } else {
          writeTransaction(() -> {
            setPreExceptMovement(false);
            setExpectMovement(true);
          });
        }
        user.getChannel().flush();
      }
    }
  }

  @Override
  void handleInput(@NotNull PlayerInputPacket packet) {
    final float forward = Math.abs(packet.getForward());
    final float sideways = Math.abs(packet.getSideways());
    final float maxBoatSpeed = user.isGeyser() ? 1 : 0.98f;
    checkState(forward <= maxBoatSpeed, "illegal speed (f): " + forward);
    checkState(sideways <= maxBoatSpeed, "illegal speed (s): " + sideways);
    // The client does not actively dismount from the vehicle.
    //if (packet.isJump() || packet.isUnmount()) return;
    if (exemptRotation) {
      handleRotation(null);
    } else {
      checkState(inputPackets + 1 == rotationPackets,
        "illegal packet order; i/r " + inputPackets + "/" + rotationPackets);
    }
    checkState(exemptPaddleBoat || inputPackets + 1 == paddlePackets,
      "illegal packet order; i/p " + inputPackets + "/" + paddlePackets);
    checkState(exemptPaddleBoat || inputPackets == vehicleMovePackets,
      "illegal packet order; i/v " + inputPackets + "/" + vehicleMovePackets);
  }

  @Override
  void handleVehicleMove(@NotNull VehicleMovePacket packet) {
    if (!exemptPaddleBoat) {
      // The last packet should be VehicleMovePacket. Check the packet order.
      checkState(vehicleMovePackets + 1 == paddlePackets,
        "illegal packet order; v/p " + vehicleMovePackets + "/" + paddlePackets);
      checkState(exemptRotation || vehicleMovePackets + 1 == rotationPackets,
        "illegal packet order; v/r " + vehicleMovePackets + "/" + rotationPackets);
      checkState(vehicleMovePackets + 1 == inputPackets,
        "illegal packet order; v/i " + vehicleMovePackets + "/" + inputPackets);
    }
  }

  @Override
  void destroy() {
    user.delayedWrite(removeBoatEntities);
  }

  @Override
  void spawn() {
    user.delayedWrite(spawnBoatEntity);
    user.delayedWrite(setBoatPassengers);
  }
}
