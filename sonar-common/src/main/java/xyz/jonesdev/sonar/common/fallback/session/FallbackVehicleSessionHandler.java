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

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.api.fallback.FallbackUser;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.*;

import static xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion.MINECRAFT_1_8;
import static xyz.jonesdev.sonar.common.fallback.protocol.FallbackPreparer.IN_AIR_Y_POSITION;

/**
 * An abstract class for helping vehicle checks.
 * <ul>
 * <li>
 *   First of all. Packets that send the spawn entity ({@link SpawnEntityPacket} and {@link SetPassengersPacket}
 *   should be send by method {@link #spawn()}). The entity will be spawned at y 1337.
 * </li>
 * <li>
 *   Sending a transaction ensures that the riding on the vehicle.
 *   After, If a movement packet is sent. The checks will fail.</li>
 * <li>
 *   If a packet is received about the vehicle. triggers the relevant method.
 *   ({@link #handleRotation}, {@link #handlePaddleBoat}, {@link #handleVehicleMove} and {@link #handleInput})
 * </li>
 * <li>
 *   If need to check the behavior of the player getting off the vehicle.
 *   Method {@link #setPreExceptMovement(boolean)} can be used to mark the player's is ready get off from the vehicle.
 *   If it has been confirmed that the player has disembarked from the vehicle. (usually sending a transaction)
 *   You can set the {@link #setExpectMovement(boolean)} method to true. (and also set {@link #setPreExceptMovement(boolean)} to false)
 *   At this time, if the player sends a packet about the vehicle. They will be marked as failed verification.
 * </li>
 * </ul>
 *
 * {@link #paddlePackets}, {@link #inputPackets}, {@link #positionPackets}, {@link #rotationPackets}
 * and {@link #vehicleMovePackets} are counted by this abstract class. (After triggered relevant method.)
 */
abstract class FallbackVehicleSessionHandler extends FallbackSessionHandler {
  protected final boolean forceCAPTCHA;
  private int expectedTransactionId;
  private @Nullable Runnable transactionListener;
  protected int paddlePackets, inputPackets, positionPackets, rotationPackets, vehicleMovePackets;
  private boolean waitingTransaction, ignorePacketUntilTransaction;
  @Getter
  @Setter
  private boolean expectMovement, preExceptMovement;

  public FallbackVehicleSessionHandler(final @NotNull FallbackUser user,
                                       final @NotNull String username,
                                       final boolean forceCAPTCHA) {
    super(user, username);
    this.forceCAPTCHA = forceCAPTCHA;

    // Spawn & Transaction
    setPreExceptMovement(true);
    spawn();
    writeTransaction(() -> setPreExceptMovement(false));
    user.getChannel().flush();
  }

  @Override
  public void handle(@NotNull FallbackPacket packet) {
    if (packet instanceof TransactionPacket) {
      checkState(waitingTransaction, "not waiting transaction");
      final TransactionPacket transaction = (TransactionPacket) packet;
      // Make sure the transaction was accepted
      // This must - by vanilla protocol - always be accepted
      checkState(transaction.isAccepted(), "didn't accept transaction");
      // Also check if the transaction ID matches the expected ID
      checkState(expectedTransactionId == transaction.getTransactionId(),
        "expected T ID " + expectedTransactionId + ", but got " + transaction.getTransactionId());
      waitingTransaction = false;
      final Runnable listener = transactionListener; // Null safely
      if (listener != null) listener.run();
      return;
    }
    if (waitingTransaction && ignorePacketUntilTransaction) return;

    if (packet instanceof SetPlayerPositionRotationPacket) {
      if (expectMovement) {
        final SetPlayerPositionRotationPacket posRot = (SetPlayerPositionRotationPacket) packet;
        handleMovement(posRot.getY());
      } else {
        checkState(preExceptMovement, "invalid packet order (unexpected SetPlayerPositionRotationPacket)");
      }
    } else if (packet instanceof SetPlayerPositionPacket) {
      if (expectMovement) {
        final SetPlayerPositionPacket position = (SetPlayerPositionPacket) packet;
        handleMovement(position.getY());
      } else {
        checkState(preExceptMovement, "invalid packet order (unexpected SetPlayerPositionPacket)");
      }
    } else if (packet instanceof SetPlayerRotationPacket) {
      handleRotation((SetPlayerRotationPacket) packet);
      rotationPackets++;
    } else if (packet instanceof SetPlayerOnGround) {
      user.fail("impossible to sent on ground packet");
    } else if (packet instanceof PaddleBoatPacket) {
      checkState(isPreExceptMovement() || !isExpectMovement(),
        "invalid packet order (unexpected PaddleBoatPacket)");
      handlePaddleBoat((PaddleBoatPacket) packet);
      paddlePackets++;
    } else if (packet instanceof VehicleMovePacket) {
      checkState(isPreExceptMovement() || !isExpectMovement(),
        "invalid packet order (unexpected VehicleMovePacket)");
      handleVehicleMove((VehicleMovePacket) packet);
      vehicleMovePackets++;
    } else if (packet instanceof PlayerInputPacket) {
      checkState(isPreExceptMovement() || !isExpectMovement(),
        "invalid packet order (unexpected PlayerInputPacket)");
      handleInput((PlayerInputPacket) packet);
      inputPackets++;
    }
  }

  abstract void markSuccess();

  // We can abuse the entity remove mechanic and check for position packets when the entity dies
  void handleMovement(double y) {
    if (user.getProtocolVersion().compareTo(MINECRAFT_1_8) < 0) {
      y -= 1.62f; // Account for 1.7 bounding box
    }
    // Check the Y position of the player
    checkState(y <= IN_AIR_Y_POSITION, "invalid y position");
    // Mark this check as successful if the player sent a few position packets
    if (positionPackets++ > Sonar.get().getConfig().getVerification().getVehicle().getMinimumPackets()) {
      markSuccess();
    }
  }

  abstract void handleRotation(final SetPlayerRotationPacket packet);

  abstract void handlePaddleBoat(final @NotNull PaddleBoatPacket packet);
  abstract void handleVehicleMove(final @NotNull VehicleMovePacket packet);
  abstract void handleInput(final @NotNull PlayerInputPacket packet);
  abstract void destroy();
  abstract void spawn();

  protected void writeTransaction(final @Nullable Runnable listener) {
    if (!waitingTransaction) {
      this.ignorePacketUntilTransaction = false;
      transactionListener = listener;
      expectedTransactionId = (short) -(RANDOM.nextInt(Short.MAX_VALUE));
      user.delayedWrite(new TransactionPacket(0, expectedTransactionId, false));
      waitingTransaction = true;
    }
  }
}
