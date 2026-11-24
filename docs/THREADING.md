# Threading model

LegendaryPlugin targets Paper 1.21.4 and Java 21. Folia is not supported;
`plugin.yml` declares `folia-supported: false`.

## Main-thread-only work

Use the server primary thread for Bukkit world/entity/player state and actions,
including `Player`/`OfflinePlayer` access, permission checks, inventory and
message operations, teleport/kick/ban, command dispatch, world/chunk/block
access, plugin registration, and module lifecycle callbacks. Do not retain
mutable Bukkit objects for use from asynchronous callbacks.

`Sync.run(plugin, action)` runs immediately when already on the primary thread
or schedules the action with Paper's synchronous scheduler otherwise.
`Sync.assertMain()` is available at API boundaries to fail fast if a caller
violates this rule.

## Asynchronous work

Async tasks may perform JDBC operations, bounded file/network I/O, cryptographic
verification, and immutable data transformations. They must not call Bukkit
state APIs. Copy the needed primitive/immutable data before leaving the main
thread, and use `Sync.run` to apply results or notify players.

ProtocolLib packet callbacks may execute on Netty threads. They may inspect
packet data and copy the player's UUID only for correlating atomic counters;
they must not inspect permissions or player/world state. Resolve the UUID to a
Player and perform anti-cheat actions on the main thread. Plugin-message
handlers validate payload bytes before scheduling player/world actions through
`Sync.run`.

Avoid blocking the main thread on futures, JDBC, DNS, or network requests.
