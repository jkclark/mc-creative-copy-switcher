# Creative Copy Switcher

A client-side Fabric mod for Minecraft 26.3 that makes it one click to jump from a survival world into a
creative copy of it (to test a build), and one click to get back.

## What it does

**In a survival world**, the pause menu gets an **Open in Creative** button, which opens a menu with:

- **Open in new copy**: saves and leaves the world, copies it to `<world name> - creative #<n>` (one higher
  than the highest existing copy of this world), switches the copy to creative with cheats on (and hardcore
  off), and opens it.
- **Open in most recent copy**: opens the highest-numbered existing copy. Greyed out if there are none.
  Hover it to see which copy it will open.

**In a creative copy**, that button is replaced by **Return to Survival**, which saves and reopens the
original world.

Copies are linked to their original by a small `creativecopy.json` file in the copy's world folder, not by
name, so renaming the original world doesn't lose track of its copies. The buttons only appear in
single-player (not on servers or while open to LAN).

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3.
2. Put `creative-copy-switcher-<version>.jar` in your `.minecraft/mods` folder
   (on Windows: `%APPDATA%\.minecraft\mods`).

Fabric API is **not** required.

## Building

Requires JDK 25.

```sh
./gradlew build                # jar ends up in build/libs/
./gradlew runClientGameTest    # end-to-end test: drives the real game through the whole flow
```

The game test opens a Minecraft window and clicks through: survival → new copy #1 → back → most recent
copy → back → new copy #2 → back → most recent copy (#2), checking the world name, game mode, cheats,
hardcore flag and that a placed block survived the copy at each step. Screenshots land in
`build/run/clientGameTest/screenshots/`.
