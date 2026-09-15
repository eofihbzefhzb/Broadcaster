# MCXboxBroadcast NetherNet Fork

Publishes a joinable Xbox session so Bedrock players can join a Java server straight from
their Xbox and Minecraft friends list. The gameplay connection goes into Geyser over NetherNet;
this process only publishes the session.

This README covers what this fork does. For everything else, see upstream
[MCXboxBroadcast/Broadcaster](https://github.com/MCXboxBroadcast/Broadcaster).

## The three forks

This fork is one of three that work together. Each README lists what its own fork changes; the
setup guide for the whole stack is this one.

| Fork | Upstream | Role |
|---|---|---|
| **Broadcaster** (this repo) | [MCXboxBroadcast/Broadcaster](https://github.com/MCXboxBroadcast/Broadcaster) | Publishes the Xbox session players see in their friends list |
| [Geyser](https://github.com/eofihbzefhzb/Geyser) | [GeyserMC/Geyser](https://github.com/GeyserMC/Geyser) | Runs the portal bridge, which accepts those players' NetherNet connections. Velocity only |
| [NetworkCompatible](https://github.com/eofihbzefhzb/NetworkCompatible) | [Kas-tle/NetworkCompatible](https://github.com/Kas-tle/NetworkCompatible) | The NetherNet transport library the portal bridge is built on |

```text
Bedrock player
  |  sees the server in the friends list  <-  Xbox session, published by Broadcaster
  |  joins over NetherNet, signaled through Xbox
  v
Geyser portal bridge  (transport: NetworkCompatible)
  v
Velocity  ->  Paper
```

The two processes run on the same machine and share two files:

- `cache/cache.json` is Broadcaster's Xbox sign-in. Geyser reads the Minecraft token in it to
  connect to Xbox signaling as the same account.
- `portal-session-status.json` is written by Geyser every 5 seconds with its NetherNet ID,
  whether it is ready, its MOTD and player counts. Broadcaster publishes that ID in the session.

## What this fork changes

### NetherNet: Geyser hosts the connection

- Upstream's own NetherNet listener is removed, together with its transfer to the server address
  (`RedirectPacketHandler`), the WebRTC dependencies and the `ice-port-range` option. A
  transferred player leaves the Xbox session, which hides the server from their friends. Here the
  player stays in the session while playing through Geyser.
- The session advertises the portal bridge's NetherNet ID: `nether-net.external-network-id`, or
  when that is empty, the ID in Geyser's `portal-session-status.json`. Nothing is published
  without one.
- At startup the Xbox sign-in is refreshed first, including the Minecraft token Geyser uses, then
  the publisher waits up to `discovery-timeout-seconds` for Geyser. After that it follows a
  change of ID, and leaves the session unchanged while Geyser's status file is not ready or is
  more than 3 minutes old.
- With `session.sync-from-geyser` on (the default), the host name, world name and player counts
  come from that status file. Without the file, they come from a ping to `session-info` when
  `query-server` is on.
- The session advertises Bedrock 26.50 (protocol 2193).

### The Xbox session

- Published as public (`BroadcastSetting` 4) with `LanGame` on. Xbox's `followed` restrictions
  still decide who sees it; see [Session visibility](#session-visibility).
- The session ID is stored in `cache/session_id.txt` and reused after a restart, so the players
  already in it stay members.
- At 28 of its 30 members the primary account moves to a fresh session in place, at most once a
  minute, instead of restarting every account. It keeps hosting the earlier sessions, writing the
  nonces their players' friends need to join: up to 5 at once, each for at most 12 hours, saved in
  `cache/retired_session.json`.
- A dropped RTA websocket re-publishes the same session rather than creating a new one.
- Session updates are retried on HTTP 429 and 5xx, honouring `Retry-After`, and Xbox's error body
  is logged when one fails.

### Sub-accounts

- A sub-account publishes no session of its own. It joins the primary session as a member and
  points its activity handle at it, and it issues no nonces.
- Sub-accounts are refreshed on every update cycle, re-pointed at the new session after a
  rotation, and leave the session they moved away from.

### Logging and defaults

- Players joining and leaving the Xbox session are logged, with how long they stayed. The
  publisher's own accounts are left out.
- Failed friend syncs are logged at debug level; the next cycle retries them.
- `friend-sync.expiry.enabled` defaults to off. Nothing in this fork records a friend's visits,
  so turned on it would unfriend everyone `days` after they were first seen.

### Builds

- The Geyser extension (`bootstrap/geyser`) and Modrinth publishing are removed.
  `MCXboxBroadcastStandalone.jar` is the only artifact.
- Releases are numbered GitHub builds whose notes list the commits since the previous build.
  Upstream's Pterodactyl egg and Docker image are not published.

## Setup

### Requirements

- Java 17 or newer for the publisher
- Velocity, in front of a Paper backend, on a Java version the paired Geyser build supports
- Floodgate on Velocity, if Geyser uses `auth-type: floodgate`
- The [Geyser fork](https://github.com/eofihbzefhzb/Geyser/releases/latest) installed as
  `Geyser-Velocity.jar`
- An Xbox/Microsoft account allowed to publish the session

### Directory layout

The publisher finds Geyser's status file on its own when it runs from a folder next to
Velocity's; otherwise set `nether-net.status-file-path`.

```text
stack/
  velocity.jar
  plugins/
    Geyser-Velocity.jar
    floodgate-velocity.jar
  mcxbox-standalone/
    MCXboxBroadcastStandalone.jar
    config.yml
    cache/
```

Do not commit or share `mcxbox-standalone/cache/cache.json`: it holds the publisher's Xbox
sign-in.

### Geyser

In Geyser's `config.yml`, enable the portal bridge and point it at the publisher's cache, using an
absolute path:

```yaml
advanced:
  bedrock:
    portal-bridge:
      enabled: true
      xbox-auth-header-file: /absolute/path/to/stack/mcxbox-standalone/cache/cache.json
      nether-net-network-id: ''
      debug-logging: false
```

The token is read locally and never logged. Keep both processes on the same trusted machine.

### Broadcaster

In `mcxbox-standalone/config.yml`, leave the network ID empty so it is read from Geyser's status
file:

```yaml
nether-net:
  external-network-id: ''
  status-file-path: ''
  discovery-timeout-seconds: 120

friend-sync:
  auto-follow: true
  auto-unfollow: true
  initial-invite: true
  expiry:
    # Leave off: nothing records visits in this fork, so every friend would be
    # dropped `days` after first being seen.
    enabled: false
```

### Starting

Start Velocity first, or the publisher first as long as Geyser is ready within
`discovery-timeout-seconds` (120 by default); the publisher exits if no ready status file
appears by then. Geyser waits up to 60 seconds for `cache.json`, then keeps retrying its NetherNet
bind in the background, every 10 seconds at first and backing off to once a minute, until the
token works. No ID has to be copied by hand.

```bash
java -jar MCXboxBroadcastStandalone.jar
```

## Session visibility

Sub-accounts join the primary account's session as members and point their Xbox activity handle
at it, so a player browsing a sub-account's profile sees the primary world and joins through it.
Each sub-account carries its own friends list, which is how the setup grows past the 2000-friend
cap of a single account.

The session is published as public (`BroadcastSetting` 4) with Minecraft's `joinable_by_friends`
joinability, and Xbox's read and join restrictions stay on `followed`. That gate decides who can
see it: anyone followed by a member of the session. Players who join become members, so every
player in the session opens it to the people they follow, for as long as they stay.

A session holds 30 members. At 28 the publisher moves its accounts to a fresh session and keeps
hosting the earlier one until its last player leaves, so the friends of the players still in it
can go on joining. Up to five earlier sessions are hosted at once. When a sixth would be needed,
the one with the fewest players is let go. Each is hosted for at most 12 hours, and a restart of
the publisher picks them up again from `cache/retired_session.json`.

Nothing widens the audience beyond that. Both obvious attempts were tried:

- A `joinable_by_friends_of_friends` joinability breaks joining outright. The client connects,
  completes the Bedrock handshake, then goes silent and times out, direct friends included.
- Read and join restrictions of `none` are rejected by Xbox with HTTP 400: *Invalid session
  'readRestriction' provided, cannot be set to none on sessions with the
  'userAuthorizationStyle' capability.* Minecraft's session template has that capability, so the
  session fails to publish.

Beyond the players themselves, reach comes from the accounts' friends lists. Each account holds
up to 2000 friends and `auto-follow` follows back everyone who follows it, so adding sub-accounts
is what widens the audience.

## Console

```text
accounts list          # every account and how many people it follows
accounts add <id>      # add a sub-account; it asks for that account's Xbox sign-in
accounts remove <id>   # remove a sub-account and its cached sign-in
dumpsession            # write the last and current session documents to files
restart                # restart session publishing
version, help, stop
```

## Diagnosing joins

Players should join from the Xbox or Minecraft friends list. Two lines are logged without any
debug setting:

- Broadcaster logs each player leaving the Xbox session with how long they stayed
  (`... is no longer in the Xbox session after 3s`). A stay of a few seconds usually means the
  join failed on the player's side, before or while connecting.
- Geyser logs a NetherNet player who never got in, with the last step reached and the disconnect
  reason: `[proxy-bridge] <player> did not get in over NetherNet (at the resource pack screen): ...`

The message the player saw on their own screen never reaches the server.

With `debug-logging: true` under Geyser's `portal-bridge`, a join logs these stages in the
Velocity log, in order. Without it, only the last line is printed.

```text
[proxy-bridge] NetherNet offer received
[proxy-bridge] NetherNet signal sent / received
[proxy-bridge] NetherNet Bedrock session initialized
[proxy-bridge] Bedrock authentication completed for <player>
[proxy-bridge] resource pack info sent to <player>
[proxy-bridge] resource pack response from <player>: status=COMPLETED
[proxy-bridge] Floodgate authentication completed for <player>
[proxy-bridge] <player> joined over NetherNet from <address>
```

If a join fails, look at the last stage logged:

- No offer: session publication, account visibility, or Xbox signaling
- Offer and signals, but no Bedrock session: NAT/ICE or NetherNet transport failure
- Bedrock session, but no authentication: Bedrock protocol or Xbox login failure
- Resource pack info, but no `status=COMPLETED`: the player quit at the resource pack prompt, or
  refused a pack that `force-resource-packs` makes mandatory
- Floodgate, but no "joined over NetherNet": Floodgate key or Java/Paper connection failure

## Releases

| Fork | What to install |
|---|---|
| Broadcaster | `MCXboxBroadcastStandalone.jar` from [releases](https://github.com/eofihbzefhzb/Broadcaster/releases/latest) |
| Geyser | `Geyser-Velocity.jar` from [releases](https://github.com/eofihbzefhzb/Geyser/releases/latest) |
| NetworkCompatible | Nothing: Geyser pulls it from JitPack at build time |
