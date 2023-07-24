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

package jones.sonar.velocity.command

import com.github.benmanes.caffeine.cache.Caffeine
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.proxy.Player
import jones.sonar.api.Sonar
import jones.sonar.common.command.CommandInvocation
import jones.sonar.common.command.InvocationSender
import jones.sonar.common.command.subcommand.SubCommand
import jones.sonar.common.command.subcommand.SubCommandRegistry
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class SonarCommand : SimpleCommand {
  override fun execute(invocation: SimpleCommand.Invocation) {
    if (invocation.source() !is ConsoleCommandSource) {
      // Checking if it contains will only break more since it can throw
      // a NullPointerException if the cache is being accessed from parallel threads
      val timestamp = DELAY.asMap().getOrDefault(invocation.source(), -1L)
      val currentTimestamp = System.currentTimeMillis()

      // There were some exploits with spamming commands in the past,
      // Spamming should be prevented, especially if some heavy operations are done,
      // which is not the case here but let's still stay safe!
      if (timestamp > 0L) {
        invocation.source().sendMessage(Component.text(Sonar.get().config.COMMAND_COOL_DOWN))

        // Format delay
        val left = 0.5 - (currentTimestamp - timestamp.toDouble()) / 1000.0

        invocation.source().sendMessage(
          Component.text(
            Sonar.get().config.COMMAND_COOL_DOWN_LEFT
              .replace("%time-left%", decimalFormat.format(left))
          )
        )
        return
      }

      DELAY.put(invocation.source(), currentTimestamp)
    }

    var subCommand = Optional.empty<SubCommand>()

    val invocationSender = InvocationSender { message -> invocation.source().sendMessage(Component.text(message)) }

    if (invocation.arguments().isNotEmpty()) {
      // Search subcommand if command arguments are present
      subCommand = SubCommandRegistry.getSubCommands().parallelStream()
        .filter { sub: SubCommand ->
          (sub.info.name.equals(invocation.arguments()[0], true)
            || (sub.info.aliases.isNotEmpty()
            && Arrays.stream(sub.info.aliases)
            .anyMatch { alias: String -> alias.equals(invocation.arguments()[0], true) }))
        }
        .findFirst()

      // Check permissions for subcommands
      if (subCommand.isPresent) {
        if (!subCommand.get().info.onlyConsole
          && !invocation.source().hasPermission(subCommand.get().permission)
        ) {
          invocationSender.sendMessage(
            Sonar.get().config.SUB_COMMAND_NO_PERM
              .replace("%permission%", subCommand.get().permission)
          )
          return
        }
      }
    }

    subCommand.ifPresentOrElse({
      if (it.info.onlyPlayers && invocation.source() !is Player) {
        invocationSender.sendMessage(Sonar.get().config.PLAYERS_ONLY)
        return@ifPresentOrElse
      }

      if (it.info.onlyConsole && invocation.source() !is ConsoleCommandSource) {
        invocationSender.sendMessage(Sonar.get().config.CONSOLE_ONLY)
        return@ifPresentOrElse
      }

      val commandInvocation = CommandInvocation(
        if (invocation.source() is Player) (invocation.source() as Player).username else "Console",
        invocationSender,
        it,
        invocation.arguments()
      )

      // The subcommands has arguments which are not present in the executed command
      if (it.info.arguments.isNotEmpty()
        && commandInvocation.arguments.size <= 1
      ) {
        invocationSender.sendMessage(
          Sonar.get().config.INCORRECT_COMMAND_USAGE
            .replace("%usage%", "${it.info.name} (${it.arguments})")
        )
        return@ifPresentOrElse
      }

      // Execute the sub command with the custom invocation properties
      it.execute(commandInvocation)
    }) {
      // Re-use the old, cached help message since we don't want to scan
      // for each subcommand and it's arguments/attributes every time
      // someone runs /sonar since the subcommand don't change
      if (CACHED_HELP.isEmpty()) {
        CACHED_HELP.add(Component.empty())
        CACHED_HELP.add(
          Component.text(
            " §eRunning §lSonar §e"
              + Sonar.get().version
              + " on "
              + Sonar.get().platform.displayName
          )
        )
        CACHED_HELP.add(Component.empty())
        CACHED_HELP.add(
          Component.text(
            " §7Need help?§b https://jonesdev.xyz/discord/"
          ).hoverEvent(
            HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text("§7Click to open Discord"))
          ).clickEvent(
            ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL, "https://jonesdev.xyz/discord/")
          )
        )
        CACHED_HELP.add(Component.empty())

        SubCommandRegistry.getSubCommands().forEach(Consumer { sub: SubCommand ->
          var component = Component.text(
            " §a▪ §7/sonar "
              + sub.info.name
              + " §f"
              + sub.info.description
          )

          component = component.clickEvent(
            ClickEvent.clickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/sonar " + sub.info.name + " ")
          ).hoverEvent(
            HoverEvent.hoverEvent(
              HoverEvent.Action.SHOW_TEXT, Component.text(
                "§7Only players: §f" + (if (sub.info.onlyPlayers) "§a✔" else "§c✗")
                  + "\n§7Require console: §f" + (if (sub.info.onlyConsole) "§a✔" else "§c✗")
                  + "\n§7Permission: §f" + sub.permission
                  + "\n§7Aliases: §f" + sub.aliases
              )
            )
          )
          CACHED_HELP.add(component)
        })

        CACHED_HELP.add(Component.empty())
      }

      CACHED_HELP.forEach {
        invocation.source().sendMessage(it)
      }
    }
  }

  // Tab completion handling
  override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
    return if (invocation.arguments().size <= 1) {
      if (TAB_SUGGESTIONS.isEmpty()) {
        for (subCommand in SubCommandRegistry.getSubCommands()) {
          TAB_SUGGESTIONS.add(subCommand.info.name)

          if (subCommand.info.aliases.isNotEmpty()) {
            TAB_SUGGESTIONS.addAll(subCommand.info.aliases)
          }
        }
      }
      TAB_SUGGESTIONS
    } else if (invocation.arguments().size == 2) {
      if (ARG_TAB_SUGGESTIONS.isEmpty()) {
        for (subCommand in SubCommandRegistry.getSubCommands()) {
          ARG_TAB_SUGGESTIONS[subCommand.info.name] = subCommand.info.arguments
            .map { argument -> argument.name }
            .toList()
        }
      }

      val subCommandName = invocation.arguments()[0].lowercase()
      ARG_TAB_SUGGESTIONS.getOrDefault(subCommandName, emptyList())
    } else emptyList()
  }

  // Permission handling
  override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
    return invocation.source().hasPermission("sonar.command")
  }

  companion object {
    private val TAB_SUGGESTIONS = ArrayList<String>()
    private val ARG_TAB_SUGGESTIONS = HashMap<String, List<String>>()
    private val DELAY = Caffeine.newBuilder()
      .expireAfterWrite(500L, TimeUnit.MILLISECONDS)
      .build<CommandSource, Long>()
    private val decimalFormat = DecimalFormat("#.##")
    private val CACHED_HELP = Vector<Component>()
  }
}