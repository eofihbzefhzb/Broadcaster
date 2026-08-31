# MCXboxBroadcast NetherNet Fork

This fork is focused on one job: publish an Xbox joinable session for a Geyser-based server where the real gameplay join terminates inside a paired Geyser NetherNet fork.

This shows up to the authenticated accounts friends in-game as a joinable session. This work was built to bring back something the Bedrock community lost a long time ago: joining and inviting directly from the game. It also prepares for a future friends-of-friends flow, so players can join while their friends are already on your server.

It is not documented here as the stock upstream project. This README only covers the fork behavior added in this repo.

## What This Fork Adds

- `external-hosted` NetherNet publish mode for pairing with a separate Geyser ingress host
- a standalone jar release for Xbox session publishing
- bridge-first defaults with no transfer fallback in the gameplay path
- docs and config guidance for local-device deployments

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

MCXboxBroadcast does not open a second Bedrock listener in `external-hosted`
mode. Geyser owns the live NetherNet connection and Paper owns the Java game.

### Requirements

- Java 25 for the current development builds
- Velocity, in front of a Paper 1.21.11 backend (or the Java version selected by the paired Geyser build)
- ViaVersion and Floodgate installed on Velocity
- The companion Geyser fork installed as `Geyser-Velocity.jar`; it is the only bootstrap that fork builds
- An Xbox/Microsoft account that is allowed to publish the session
- Bedrock players who can see the publisher through the Xbox friends/session UI

### Recommended directory layout

The standalone publisher discovers Geyser's status file automatically when it
runs from a sibling directory:

```text
stack/
  velocity.jar
  plugins/
    Geyser-Velocity.jar
    floodgate-velocity.jar
    ViaVersion.jar
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
  external-hosted: true
  external-network-id: ''
  discovery-timeout-seconds: 120

xbox-session:
  # Keep joinable_by_friends. Other values (including joinable_by_friends_of_friends) break
  # joining: the client connects, completes the Bedrock handshake, then stops responding.
  joinability: joinable_by_friends
  # Xbox MPSD gates. Both must stay "followed" - see "Session visibility" below.
  read-restriction: followed
  join-restriction: followed

friend-sync:
  auto-follow: false
  auto-unfollow: false
  initial-invite: false
  expiry:
    enabled: false
```

Startup order does not matter. Geyser retries its NetherNet bind every 10 seconds
until the Xbox auth source is usable and waits up to 60 seconds for the cache file
to appear; the publisher in turn waits for a ready `portal-session-status.json`
before publishing. Whichever starts first waits for the other. No ID copying is
required.

### Session visibility

Sub-accounts do not publish sessions of their own. They join the primary account's
session as members and point their Xbox activity handle at it, so a player browsing
a sub-account's profile sees the primary world and joins through it. Each sub-account
carries its own friends list, which is how the setup scales past the 2000-friend cap
on a single account.

There is no setting that opens the world to friends-of-friends. All three candidates
were tried and none works:

- `joinability: joinable_by_friends_of_friends` breaks joining outright. The client
  connects, completes the Bedrock handshake, then goes silent and times out - direct
  friends included.
- `read-restriction: none` and `join-restriction: none` are rejected by Xbox with
  HTTP 400: *Invalid session 'readRestriction' provided, cannot be set to none on
  sessions with the 'userAuthorizationStyle' capability.* Minecraft's session template
  carries that capability, so the session simply fails to publish.

Reach comes from the accounts' friends lists instead. Each account holds up to 2000
friends and `auto-follow` accepts incoming requests automatically, so adding
sub-accounts is what widens the audience.

The standalone console provides two safe operational commands:

```text
status                 # session, NetherNet ID, PmsgId presence, health
invite <xuid>          # one explicit invitation; validated and rate-limited
```

Automatic friend-list changes and bulk invitations are disabled by default.

### Joining and diagnosing

The Bedrock player should join from the Xbox/Minecraft friends session list.
The expected server log sequence is:

```text
session created
-> NetherNet offer/signaling
-> NetherNet peer connected
-> Bedrock session initialized
-> Floodgate authentication completed
-> Java/Paper connection established
```

If a join fails, inspect the Paper/Geyser log and classify the last stage:

- no offer: session publication, account visibility, or Xbox signaling
- offer/signals but no peer: NAT/ICE or transport failure
- peer but no Bedrock session: Bedrock protocol/NetherNet transport failure
- Bedrock session but no Floodgate: authentication or Floodgate key setup
- Floodgate but no Paper connection: Java/Paper or server shutdown failure

The client message “NetherNet” or “Door” is only a generic symptom; the
server-side stage is the useful diagnosis.

## Recommended Layout

Use this fork together with the companion Geyser fork in `eofihbzefhzb/Geyser`.

Recommended runtime layout:

1. `MCXboxBroadcastStandalone.jar` publishes the Xbox Live session
2. `Geyser-Velocity.jar` from the companion fork hosts the real NetherNet/Bedrock ingress
3. Bedrock gameplay traffic terminates in Geyser, not in `mcxba`
That removes the old gameplay relay bottleneck and is the smoothest setup from this work.

## Releases

Current release line:

- Build `2`

Assets:

- `MCXboxBroadcastStandalone.jar`

Release page:

- https://github.com/eofihbzefhzb/Broadcaster/releases/latest

## Which Jar To Use

`MCXboxBroadcastStandalone.jar` is the only jar this fork builds: the Geyser extension form
was removed because Geyser's own NetherNet ingress now owns the gameplay connection, leaving
this process responsible only for publishing the Xbox session.

Run:

```bash
java -jar MCXboxBroadcastStandalone.jar
```

## Config Note For Local Device Installs

If `mcxba` and the real Geyser NetherNet ingress are on the same local device, you do not need to use your router-forwarded public Bedrock port in `config.yml`.

In `external-hosted` mode, the important join identifier is the NetherNet network ID. The config can stay on the local or LAN listener that actually matches your Bedrock-side host.

## Companion Fork

Use this with:

- https://github.com/eofihbzefhzb/Geyser
## Scope

This README is intentionally limited to the NetherNet fork behavior added here. For the original upstream project history and broader feature set, see the upstream `MCXboxBroadcast/Broadcaster` repository.
