# Big Statics
## What is it?
A Paper / Folia Minecraft plugin that adds building constraints requiring floating player-made structures to have support pillars.

## How does it impact gameplay?
Whenever a player tries to place a block, the plugin compares the new block's position with a heightmap to check if it is floating above the surface.

## What is the main purpose?
This plugin is designed to heavily limit the construction of "sky bases/bridges".

## How does it work?
When a chunk is generated the plugin will create a heightmap of that chunk.
Every time a block gets placed, a **Breadth-first search** pathfinding algorithm is used to compute the complexity of the new block's position.
This is done by iterating over every connected (solid) block and only stopping if either the maximum complexity threshold is reached or a connected block that has a Y position that is **smaller or equal** to the stored heightmap's Y position at the same X and Z position.
The complexity is based on the distance from the new block's X and Z position to the closest connected ground-touching block's X and Z position.
**The Y difference has no impact on complexity**.

If the complexity is too high, the block will not be placed.

## Limitations
This heightmap-based approach comes with the limitations of **not working in the nether and underground**.
Players may also build ontop of an existing tree or mountain and then remove said tree or mountain as the heightmap is created once during world generation and **never modified / updated**.

## Possible advantages over other approaches
Similar plugins exist that rely on storing PDC-data for every player-placed block.
While that approach does not have the limitations of the heightmap approach (see above), it does introduce a higher performance cost as a single block being placed may trigger *hundreds* of PDC reads in the same tick.
Big Statics stores the heightmap data of all loaded chunks (excluding blacklisted worlds like *the nether*) in memory as raw *short[256]*.

## Supported platforms
This plugin is **100% Folia compatible**.
All the heightmap-data *generation*, *loading* and *saving* is done **async**.
**Only Paper and Folia are supported**, feel free to fork for other specific ecosystems.

## Why I made this
Sky-bases and -bridges have actively ruined the balancing of my *realciv-server* known as *Big World* where the intention is to offer an experience inspired by a mix of *Factions*, *Civilization Events*, *(soft-) anarchy* and *MMOs*.

## Contact me
Feel free to contact me on on Discord.
>My Discord handle: @moos13

## License
Please read LICENSE.md for information on the licensing of this project.
