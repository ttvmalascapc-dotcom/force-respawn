# GeyserForceRespawn

`/forcerespawn <player> [force]` (alias `/frespawn`) — lets staff recover a Java or Bedrock (Geyser/Floodgate)
player stuck in the void/death softlock without making them reconnect. Works from console.

## Build status (read this)
No compiled jar is included. The environment this was written in had no Java compiler binary,
no Paper API jar and no network, so a real build was not possible there. The source was only
type-checked against hand-written API stubs. **Nothing was run on a Minecraft server.**

## Getting the jar (no local tools needed)
1. Create a free GitHub repository and upload the contents of this folder (including `.github`).
2. Open the repo's **Actions** tab. The "Build GeyserForceRespawn" workflow runs automatically on upload
   (or press **Run workflow**).
3. When it finishes, open the run and download the `GeyserForceRespawn` artifact. It contains `GeyserForceRespawn.jar`.
Or, with Maven + JDK 17+ installed: `mvn package` -> `target/GeyserForceRespawn.jar`.

## Install
Put the jar in `/plugins`, restart the server, edit `plugins/GeyserForceRespawn/config.yml` if wanted.
Geyser/Floodgate are optional (soft-depends).

## Usage
- `/forcerespawn Steve` — respawn a dead player (also accepts Floodgate names without the `.` prefix).
- `/forcerespawn Steve force` — resync a player who is alive and not in the void (needs `forcerespawn.force`).
- Permissions (all OP by default): `forcerespawn.use`, `forcerespawn.force`, `forcerespawn.bypass-cooldown`.

## How it works
1. Dead player: `Player.Spigot#respawn()` (real server respawn; fires `PlayerRespawnEvent`, sends the respawn packet). Verified after `verify-delay-ticks`; retried once.
2. Still dead: optional `dead-fallback` restores health and moves the player to a safe spawn.
3. After any recovery: fall distance/velocity cleared, a position packet and inventory are re-sent so a desynced client gets unstuck.
4. Alive but below the world's minimum height: moved to bed/anchor/world spawn.

## Limitations
- Items/XP already dropped at the moment of death are not restored (keepInventory behaves as normal).
- The dead-fallback is a last resort and may not clear every stuck state on every server build.
- Whether this fixes a given Geyser desync depends on the Geyser version; untested.
- Requires Paper/Spigot/Purpur 1.17+ (uses `World#getMinHeight`). Not Folia-compatible.
