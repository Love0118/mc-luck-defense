# Moma chat-report guard

FreedomChat commit `740608eb5a7eb9c9fbb2afd47b1ad3dfc427e38e` supplies the
protocol-777 packet encoder and pending-signature acknowledgement cleanup.
The upstream sources are checked by SHA-256; the Paper build used here requires
one compatibility edit to unwrap its optional packet signature before ack cleanup.
Both the original hashes and this edit are recorded in `upstream.json`.
This directory is a separate GPL-3.0 plugin, with the upstream license included
in its source and JAR. It is not shaded into Moma or Noqui.

The adapter applies the upstream encoder to existing connections when enabled,
tracks future connections, and removes only its own hooks when disabled. No
player is disconnected. Ordinary chat, `/msg`, `/w`, `/tell`, team messages and
`/me` and other outgoing player-chat packets become decorated system-chat packets.
Incoming account authentication and signature validation remain unchanged.
`preventsChatReports: true` is advertised for compatible NoChatReports clients.
The server does not claim that secure chat is enforced.

Build with JDK 25 and the matching Paper 26.3 server and libraries:

```powershell
python native/chat-report-guard/build.py --server-jar .runtime/ci-server-smoke/versions/26.3/paper-26.3.jar --libraries .runtime/ci-server-smoke/libraries --output .runtime/noqui-qa/queue100-stage/MomaChatGuard.jar
```

Install `MomaChatGuard.jar` alongside the game plugins. A main-thread plugin load
can enable this new module without restarting or reloading either game plugin.
The build is pinned to protocol 777; do not bypass the protocol check.

Run the isolated protocol smoke with JDK 25 and the same cached dependencies:

```powershell
python native/chat-report-guard/smoke/run.py --paperclip .runtime/ci-server/paper-paperclip-26.3.local-SNAPSHOT.jar --cache .runtime/ci-server-smoke --work .runtime/chat-guard-smoke
```

The fixture binds only to loopback and shuts itself down after checking all seven
chat types with and without signatures, formatting, system-message passthrough,
NCR status metadata, and selective acknowledgement cleanup. It does not touch
the public server. Existing signed messages in a client's history cannot be
removed retroactively; player skin/name reporting is separate from chat reports.
