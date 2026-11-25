# Creative Copy Switcher

A client-side Fabric mod for Minecraft 26.3 that makes it one click to jump from a survival world into a
creative copy of it (to test a build), and one click to get back, with the build as a Litematica schematic.

## What it does

**In a survival world**, the pause menu gets an **Open in Creative** button, which opens a menu with:

- **Open in new copy**: saves and leaves the world, copies it to `<world name> - creative #<n>` (one higher
  than the highest existing copy of this world), switches the copy to creative with cheats on (and hardcore
  off), and opens it.
- **Open in most recent copy**: opens the highest-numbered existing copy. Greyed out if there are none.
  Hover it to see which copy it will open.

**In a creative copy**, that button is replaced by:

- **Send Changes to Survival**: saves everything you changed in the copy as a Litematica schematic, opens the
  original world, and places the schematic at the same coordinates. Sending again replaces the previous
  placement. Changes in other dimensions are placed when you next go there. Litematica's rendering is turned on
  and its layer mode set to All, so the changes are visible.
- **Return to Survival**: saves and reopens the original world.

### What counts as a change

Blocks you place or break, including with commands and edit tools that change blocks on the server, falling
blocks, and TNT. Growth, mobs, flowing fluids and redstone don't count, and neither do doors and levers you
use. Item frames, paintings, armor stands, and edits to only a block's contents (sign text, chest items)
aren't included.

Each changed block's original state is recorded in `creativecopy_changes.bin` in the copy's world folder the
first time it's changed, so only copies made with version 1.1.0 or later can send changes. Unchanged blocks
inside a schematic region are taken from the survival world (which is only read), so anything built there
since the copy was made doesn't show up as a difference.

Copies are linked to their original by a small `creativecopy.json` file in the copy's world folder, not by
name, so renaming the original world doesn't lose track of its copies. The buttons only appear in
single-player (not on servers or while open to LAN).

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3.
2. Put `creative-copy-switcher-<version>.jar` in your `.minecraft/mods` folder
   (on Windows: `%APPDATA%\.minecraft\mods`), along with
   [Litematica](https://modrinth.com/mod/litematica) 0.29.1 or later and the
   [MaLiLib](https://modrinth.com/mod/malilib) version it needs.

Fabric API is **not** required.

## Building

Requires JDK 25.

```sh
./gradlew build                # jar ends up in build/libs/
./gradlew runClientGameTest    # end-to-end tests: drive the real game through the whole flow
```

The first game test opens a Minecraft window and clicks through: survival → new copy #1 → back → most recent
copy → back → new copy #2 → back → most recent copy (#2), checking the world name, game mode, cheats,
hardcore flag and that a placed block survived the copy at each step. A second test builds in a copy, sends
the changes to survival twice, and checks what Litematica placed there. Screenshots land in
`build/run/clientGameTest/screenshots/`.
