# ExoWoodCube patch: WoodCubeHarvestEvent

ExoWoodCube cancels the BlockBreakEvent of every cube log at LOWEST priority and harvests the log
itself. Other plugins never see those breaks, so ExoEconomy cannot reward them. ExoCubes solves this
with its `dev.exo.cubes.api.CubeHarvestEvent`; this patch gives ExoWoodCube the same kind of event.

The event fires once per log a player really chopped in survival, after the drop is in storage. It
does not fire for refused breaks, creative mode or Auto-Mine. It is not cancellable.

## Apply (ExoWoodCube 1.1.0 source)

1. Copy `src/main/java/dev/exo/woodcube/api/WoodCubeHarvestEvent.java` into the ExoWoodCube project, in the same path.
2. Apply the one-hunk change to `WoodService#harvest`, either with
   `git apply WoodService-harvest-event.patch` from the ExoWoodCube project folder, or by hand. The
   change adds 4 lines just before the particle effect: `Bukkit.getPluginManager().callEvent(new WoodCubeHarvestEvent(...))`.
3. Rebuild ExoWoodCube with `./gradlew build` and replace the jar on the server.

On startup ExoEconomy logs `ExoWoodCube 1.1.0 hooked: blocks mined in cubes pay woodcutting rewards`.
An unpatched ExoWoodCube logs a warning instead, and its logs pay nothing.

Other plugins can use the event too (quests, tool XP, statistics): `getPlayer()`, `getCubeId()`,
`getCubeOwner()`, `getTier()`, `getLocation()`, `getBlock()`, `getDrop()`, `getAmount()`,
`isAutosold()` and `getValue()` (money).
