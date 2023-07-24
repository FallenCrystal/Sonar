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

package xyz.jonesdev.sonar.api;

import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.config.SonarConfiguration;
import xyz.jonesdev.sonar.api.database.Database;
import xyz.jonesdev.sonar.api.fallback.Fallback;
import xyz.jonesdev.sonar.api.fallback.FallbackHolder;
import xyz.jonesdev.sonar.api.logger.Logger;
import xyz.jonesdev.sonar.api.server.ServerWrapper;
import xyz.jonesdev.sonar.api.statistics.Statistics;
import xyz.jonesdev.sonar.api.verbose.Verbose;
import xyz.jonesdev.sonar.api.version.SonarVersion;

import java.io.File;
import java.text.DecimalFormat;

public interface Sonar {
  ServerWrapper getServer();

  // used for bStats metrics
  default int getServiceId() {
    switch (getServer().getPlatform()) {
      case BUKKIT: {
        return 19110;
      }
      case BUNGEE: {
        return 19109;
      }
      default:
      case VELOCITY: {
        return 19107;
      }
    }
  }

  @NotNull File getPluginDataFolder();

  @NotNull SonarConfiguration getConfig();

  @NotNull DecimalFormat getFormatter();

  @NotNull
  default SonarVersion getVersion() {
    return SonarVersion.GET;
  }

  @NotNull
  default Fallback getFallback() {
    return FallbackHolder.INSTANCE;
  }

  @NotNull
  default Statistics getStatistics() {
    return Statistics.INSTANCE;
  }

  @NotNull
  default Database getDatabase() {
    return getConfig().DATABASE.getHolder();
  }

  @NotNull Verbose getActionBarVerbose();

  @NotNull Logger getLogger();

  void reload();

  @NotNull
  static Sonar get() {
    return SonarSupplier.get();
  }
}