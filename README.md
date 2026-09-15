# MCXboxBroadcast NetherNet Fork

This fork is focused on one job: publish an Xbox joinable session for a Geyser-based server where the real gameplay join terminates inside a paired Geyser NetherNet fork.

This shows up to the authenticated accounts friends in-game as a joinable session. This work was built to bring back something the Bedrock community lost a long time ago: joining and inviting directly from the game. It also works friends-of-friends: while a player is in the session, the people they follow can see it and join through them.

It is not documented here as the stock upstream project. This README only covers the fork behavior added in this repo; for everything else, see the upstream `MCXboxBroadcast/Broadcaster` repository.

## What This Fork Adds

- publishing for the Geyser fork's NetherNet ingress in place of upstream's own listener, whose transfer takes players out of the Xbox session and hides it from their friends
- sub-accounts that join the primary session, and rotation to a fresh session at the member cap
- a standalone jar release for Xbox session publishing

## Reliable Geyser + MCXboxBroadcast Setup

Use this repository as the Xbox session publisher and pair it with the
[companion Geyser fork](https://github.com/eofihbzefhzb/Geyser)
as the gameplay ingress. The responsibilities are deliberately separate:

```text
Bedrock client
    -> Xbox session and NetherNet signaling
    -> Geyser NetherNet ingress
    -> Paper Java server
```

MCXboxBroadcast never opens a Bedrock listener of its own. Geyser owns
the live NetherNet connection and Paper owns the Java game, so the `session-info` address
and port in `config.yml` do not need to be your public, router-forwarded Bedrock port.

### Requirements

- Java 17 or newer
- Velocity, in front of a Paper backend on a Java version the paired Geyser build supports
- Floodgate installed on Velocity, if Geyser uses `auth-type: floodgate`
- The companion Geyser fork installed as `Geyser-Velocity.jar`; it is the only bootstrap that fork builds
- An Xbox/Microsoft account that is allowed to publish the session
- Bedrock players who can see the publisher through the Xbox friends/session UI

### Recommended directory layout

The standalone publisher finds Geyser's status file on its own when it runs from a
folder next to Velocity's; otherwise set `nether-net.status-file-path`:

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

Do not commit or share `mcxbox-standalone/cache/cache.json`; it contains the
publisher's Xbox authentication data.

### Geyser configuration

In Geyser's `config.yml`, enable the portal bridge and point the auth-file
setting at the local MCXboxBroadcast cache. Use an absolute path:

```yaml
advanced:
  bedrock:
    portal-bridge:
      enabled: true
      xbox-auth-header-file: /absolute/path/to/stack/mcxbox-standalone/cache/cache.json
      nether-net-network-id: ''
      debug-logging: false
```

The auth-file is read locally and is never printed by the bridge. Keep the two
processes on the same trusted machine unless you have a secure way to provide
the cache to Geyser.

### MCXboxBroadcast configuration

In `mcxbox-standalone/config.yml`, keep the network ID empty so it is read from
Geyser's atomic readiness file:

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
    # Leave off. Players join through Geyser, not through this process, so nothing records
    # their visits and every friend would be dropped `days` after first being seen.
    enabled: false
```

Start Velocity/Geyser first, or within `discovery-timeout-seconds` (120 by default) of
the publisher: the publisher waits that long for a ready `portal-session-status.json` and
exits if none appears. Geyser waits up to 60 seconds for the cache file and retries its
NetherNet bind in the background (every 10 seconds at first, backing off to once a minute)
until the Xbox auth source is usable. No ID copying is required.

Run the publisher with:

```bash
java -jar MCXboxBroadcastStandalone.jar
```

### Session visibility

Sub-accounts do not publish sessions of their own. They join the primary account's
session as members and point their Xbox activity handle at it, so a player browsing
a sub-account's profile sees the primary world and joins through it. Each sub-account
carries its own friends list, which is how the setup scales past the 2000-friend cap
on a single account.

The session is published as public (`BroadcastSetting` 4) with Minecraft's
`joinable_by_friends` joinability, and Xbox's own read and join restrictions stay on
`followed`. That last gate decides who can actually see it: anyone followed by a member
of the session, and players who join become members, so every player in the session
opens it to the people they follow.

A session holds 30 members. At 28 the publisher moves its accounts to a fresh session and
keeps hosting the earlier one until its last player leaves, so the friends of the players
still in it can go on joining. Up to five earlier sessions are hosted at once - when a sixth would
be needed, the one with the fewest players is let go - each for at most 12 hours, and a restart
of the publisher picks them up again from `cache/retired_session.json`.

Nothing widens the audience beyond that. Both obvious attempts were tried:

- a `joinable_by_friends_of_friends` joinability breaks joining outright. The client
  connects, completes the Bedrock handshake, then goes silent and times out - direct
  friends included.
- read and join restrictions of `none` are rejected by Xbox with HTTP 400: *Invalid
  session 'readRestriction' provided, cannot be set to none on sessions with the
  'userAuthorizationStyle' capability.* Minecraft's session template carries that
  capability, so the session simply fails to publish.

Beyond the players themselves, reach comes from the accounts' friends lists. Each account
holds up to 2000 friends and `auto-follow` follows back everyone who follows it, so adding
sub-accounts is what widens the audience.

The standalone console provides:

```text
accounts list          # every account and how many people it follows
accounts add <id>      # add a sub-account; it asks for that account's Xbox sign-in
accounts remove <id>   # remove a sub-account and its cached sign-in
dumpsession            # write the last and current session documents to files
restart                # restart session publishing
version, help, stop
```

### Joining and diagnosing

The Bedrock player should join from the Xbox/Minecraft friends session list. Two lines
are always logged, without any debug setting:

- MCXboxBroadcast reports each player leaving the Xbox session with how long they stayed
  (`... is no longer in the Xbox session after 3s`). A stay of a few seconds usually means
  the join failed on the player's side before or while connecting.
- Geyser reports a NetherNet player who never got in, with the last step reached and the
  disconnect reason: `[proxy-bridge] <player> did not get in over NetherNet (at the
  resource pack screen): ...`

The message the player saw on their own screen never reaches the server.

With `debug-logging: true` under Geyser's `portal-bridge`, a join logs these stages in the
Velocity log, in order (without it only the last line is printed):

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

If a join fails, classify the last stage that was logged:

- no offer: session publication, account visibility, or Xbox signaling
- offer and signals but no Bedrock session: NAT/ICE or NetherNet transport failure
- Bedrock session but no authentication: Bedrock protocol or Xbox login failure
- resource pack info but no `status=COMPLETED`: the player quit at the resource pack prompt,
  or refused a pack that `force-resource-packs` makes mandatory
- Floodgate but no "joined over NetherNet": Floodgate key or Java/Paper connection failure

## Releases

`MCXboxBroadcastStandalone.jar` is the only jar this fork builds, published at
https://github.com/eofihbzefhzb/Broadcaster/releases/latest. The Geyser extension form was
removed because the Geyser fork's NetherNet ingress owns the gameplay connection, leaving this
process responsible only for publishing the Xbox session.
