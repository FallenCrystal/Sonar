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

package xyz.jonesdev.sonar.common.boot;

import com.alessiodp.libby.LibraryManager;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.j256.ormlite.logger.Level;
import com.j256.ormlite.logger.Logger;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.api.SonarPlatform;
import xyz.jonesdev.sonar.api.SonarSupplier;
import xyz.jonesdev.sonar.api.command.subcommand.SubcommandRegistry;
import xyz.jonesdev.sonar.api.config.SonarConfiguration;
import xyz.jonesdev.sonar.api.database.controller.VerifiedPlayerController;
import xyz.jonesdev.sonar.api.notification.ActionBarNotificationHandler;
import xyz.jonesdev.sonar.api.notification.ChatNotificationHandler;
import xyz.jonesdev.sonar.api.statistics.SonarStatistics;
import xyz.jonesdev.sonar.api.timer.SystemTimer;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPreparer;
import xyz.jonesdev.sonar.common.fallback.ratelimit.CaffeineCacheRatelimiter;
import xyz.jonesdev.sonar.common.fallback.ratelimit.NoopCacheRatelimiter;
import xyz.jonesdev.sonar.common.service.ScheduledServiceManager;
import xyz.jonesdev.sonar.common.statistics.GlobalSonarStatistics;
import xyz.jonesdev.sonar.common.subcommand.*;
import xyz.jonesdev.sonar.common.util.ProtocolUtil;

import java.io.File;
import java.time.Duration;

@Getter
@RequiredArgsConstructor
public abstract class SonarBootstrap<T> implements Sonar {
  private T plugin;
  @Setter
  private ActionBarNotificationHandler actionBarNotificationHandler;
  @Setter
  private ChatNotificationHandler chatNotificationHandler;
  private SonarConfiguration config;
  private VerifiedPlayerController verifiedPlayerController;
  private final LibraryManager libraryManager;
  private final SonarStatistics statistics;
  private final SonarPlatform platform;
  private final SubcommandRegistry subcommandRegistry;
  private final SystemTimer launchTimer = new SystemTimer();

  public SonarBootstrap(final @NotNull T plugin,
                        final @NotNull SonarPlatform platform,
                        final @NotNull File dataDirectory,
                        final @NotNull LibraryManager libraryManager) {
    // Load all libraries before anything else
    LibraryLoader.loadLibraries(libraryManager, platform);
    this.libraryManager = libraryManager;
    // Check if Netty is up to date
    ProtocolUtil.checkNettyVersion();
    // Set the Sonar API
    SonarSupplier.set(this);
    // Set the plugin instance
    this.plugin = plugin;
    this.platform = platform;
    // Load the rest of the components
    this.statistics = new GlobalSonarStatistics();
    this.actionBarNotificationHandler = new ActionBarNotificationHandler();
    this.chatNotificationHandler = new ChatNotificationHandler();
    this.config = new SonarConfiguration(dataDirectory);
    // Register all subcommands
    this.subcommandRegistry = new SubcommandRegistry();
    this.subcommandRegistry.register(
      new BlacklistCommand(),
      new VerifiedCommand(),
      new StatisticsCommand(),
      new VerboseCommand(),
      new ReloadCommand(),
      new DumpCommand(),
      new NotifyCommand());
    // Hide unnecessary debug information
    Logger.setGlobalLogLevel(Level.WARNING);
  }

  public final void initialize() {
    getLogger().info("Successfully booted in {}s!", launchTimer);
    getLogger().info("Initializing shared components...");

    // Reload configuration
    reload();

    getLogger().info("Successfully initialized components in {}s!", launchTimer);
    getLogger().info("Enabling all tasks and features...");

    try {
      // Run the per-platform initialization method
      enable();

      // Start threads
      getLogger().info("Starting all managed threads...");
      ScheduledServiceManager.start();

      // Done
      getLogger().info("Done ({}s)!", launchTimer);
    } catch (Throwable throwable) {
      // An error has occurred
      getLogger().error("An error has occurred while launching Sonar: {}", throwable);
      throwable.printStackTrace(System.err);
    }
  }

  public abstract void enable();

  public final void reload() {
    // Load the configuration
    getConfig().load();

    // Warn player if they reloaded and changed the database type
    if (verifiedPlayerController != null
      && verifiedPlayerController.getCachedDatabaseType() != getConfig().getDatabase().getType()) {
      getLogger().warn("Reloading after changing the database type is not recommended as it may cause data loss.");
    }

    // Prepare cached packets
    getLogger().info("Taking cached snapshots of all packets...");
    FallbackPreparer.prepare();

    // Update ratelimiter cache
    getFallback().setRatelimiter(getConfig().getVerification().getReconnectDelay() > 0L
      ? new CaffeineCacheRatelimiter(Duration.ofMillis(getConfig().getVerification().getReconnectDelay()))
      : NoopCacheRatelimiter.INSTANCE);

    // Update blacklist cache
    final long blacklistTime = getConfig().getVerification().getBlacklistTime();
    final boolean blacklistExists = getFallback().getBlacklist() != null;
    // Make sure the blacklist is only set when we need it to prevent data loss
    if (!blacklistExists // Make sure we create a new cache if it doesn't exist yet
      || getFallback().getBlacklistTime() != blacklistTime) {
      // Create a new cache with the configured blacklist time
      getFallback().setBlacklist(Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMillis(getConfig().getVerification().getBlacklistTime()))
        .ticker(Ticker.systemTicker())
        .build());
      // Store the new blacklist time, so we don't have to reset the blacklist every reload
      getFallback().setBlacklistTime(blacklistTime);
      // Warn the user about changing the expiry of the blacklist values
      if (blacklistExists) {
        getLogger().warn("The blacklist has been reset as the duration of entries has changed.");
      }
    }

    // Reinitialize database controller
    if (verifiedPlayerController != null) {
      // Close the old connection first
      verifiedPlayerController.close();
    }
    verifiedPlayerController = new VerifiedPlayerController(libraryManager);
  }

  public final void shutdown() {
    // Initialize the shutdown process
    getLogger().info("Starting shutdown process...");
    // Interrupt threads
    ScheduledServiceManager.stop();
    // Close database connection if present
    if (verifiedPlayerController != null) {
      verifiedPlayerController.close();
    }
    // Run the per-platform disable method
    disable();
    // Thank the user for using Sonar
    getLogger().info("Successfully shut down. Goodbye!");
  }

  public abstract void disable();
}
