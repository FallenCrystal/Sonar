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

package xyz.jonesdev.sonar.api.command.subcommand;

import lombok.Getter;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.api.command.CommandInvocation;
import xyz.jonesdev.sonar.api.command.InvocationSender;
import xyz.jonesdev.sonar.api.command.argument.Argument;

import java.util.Arrays;
import java.util.stream.Collectors;

@Getter
public abstract class Subcommand {
  private final SubcommandInfo info;
  private final String permission, aliases, arguments;
  protected static final Sonar sonar = Sonar.get();

  public Subcommand() {
    info = getClass().getAnnotation(SubcommandInfo.class);
    permission = "sonar." + info.name();
    aliases = info.aliases().length == 0 ? "No aliases."
      : String.join(", ", info.aliases());

    arguments = info.arguments().length == 0 ? ""
      : Arrays.stream(info.arguments())
      .map(Argument::value)
      .collect(Collectors.joining(", "));
  }

  protected final void incorrectUsage(final InvocationSender sender) {
    sender.sendMessage(
      sonar.getConfig().INCORRECT_COMMAND_USAGE
        .replace("%usage%", info.name() + " (" + arguments + ")")
    );
  }

  public abstract void execute(final CommandInvocation invocation);
}
