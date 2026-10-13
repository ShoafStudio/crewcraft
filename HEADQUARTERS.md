# CrewCraft setup and operation

## Private configuration

All setup files belong in `.crewcraft-home/`, which is ignored by Git. Never commit credentials, environment files, machine inventories, messages, logs, Minecraft saves, or generated snapshots. The public repository has no preconfigured connections. `node tools/setup-crewcraft.mjs` writes your first configuration; it refuses to overwrite existing files.

`machines.json` contains up to 16 machines, each with a unique `id`, `label`, optional `ssh` and numeric `port`, or a read-only inventory `snapshot` path. A machine without SSH or snapshot is the current computer. SSH uses BatchMode and strict host-key checking: authenticate and trust your host yourself before configuring it. No remote software is installed or restarted.

Machine `env` supports `HQ_SOURCES` (comma-separated Hermes/OpenClaw/Codex), `HQ_PROJECT_ROOTS` (host-platform path-separated extra project roots), `CODEX_HOME`, `OPENCLAW_STATE_DIR`, `HERMES_HOME`, `HQ_HERMES_BIN`, `HQ_HERMES_CWD`, `HQ_CODEX_THREAD_ID`, `HQ_HERMES_SNAPSHOT`, and `HQ_MONITOR_OUTPUT`. Empty project roots disable extra folder scans; runtime records can still identify their projects. Output feeds are off unless `HQ_MONITOR_OUTPUT=1` is explicitly configured.

`characters.json` contains up to six main identities. Each has a unique lowercase `id`, display `name`, `machine` ID, `source` (Hermes/OpenClaw/Codex), unique `skin` (marlow/juniper/kit/wren/rowan/tove), `chat` (default false), and `coordinator` (default false). At most one coordinator is permitted. `builder`, `team-*`, and `construction-*` are reserved for game-managed identities. The skin names describe artwork, not fixed agent identities. Runtime team membership is observed, not fabricated.

A Codex character uses an explicitly selected local chat: set `sessionReference` to `codex://threads/YOUR_CHAT_UUID`. Messaging requires a valid pinned chat and `chat:true`; it does not choose an arbitrary recent session. Remote Codex inventory is read-only; the desktop messaging bridge is local.

Use the example JSON files in `tools/` as templates. They contain no remote hosts and have messaging off. A task board is not enabled by the wizard.

## Conversations and monitors

Select an agent in the conversation sidebar or right-click its character. Drafts, unread indicators, scroll position and history are separate per conversation. General messages go only to your configured coordinator; without one, select an individual agent. There is no broadcast fallback.

Hermes and OpenClaw messages use a named persistent conversation in the existing runtime, preserving its model, tools and memory. No automatic messages are sent to external channels. Monitoring reads metadata and optional bounded visible output; never expose hidden reasoning or raw tool arguments. Main-agent identities are stable; sessions are activity records, not separate managers.

## Builder

Builder is a local planner independent of your remote agents. The local Codex CLI must be installed and authenticated for research and concept generation. Minecraft’s Mason, Carpenter and Roofer execute validated block changes.

- `/build office`: save an attached-house preset for your configured teams. The preset supports four managers: one team up to 28 people and three up to nine each. Custom layouts handle other rosters within engine limits.
- `/build preview`: view the exact pending block plan.
- `/build build`: approve that viewed proposal for execution.
- `/build status`, `/build pause`, `/build resume`, `/build undo`: control world construction.
- In Builder chat, `generate concepts`, `choose concept A` (or B/C), then ask for a plan. Concept generation and planning consume your own account usage.
- Place supplied blocks-only Sponge v2/v3 `.schem` files in `.crewcraft-home/builder/imports/`; use `schematics` and `import filename.schem at X Y Z`. Paid content must be supplied by its authorized user.

Plans are bounded to X=-56..52, Y=64..192, Z=-36..118 and 350,000 expanded block operations. Schematics also have file and content limits. No arbitrary model-generated Minecraft commands, automatic pack installs, or exact copies from screenshots are promised. The planner surveys the whole bounded site independently of player location. Back up a stopped world before a layout change. Preserve player edits and block entities.

## Optional task board and PR reader

Create `.crewcraft-home/mission-control.json` only when using a compatible workspace task API:

```json
{"baseUrl":"https://board.example.invalid","machine":"primary","envFile":"~/.config/my-board.env","humans":["Your Name"]}
```

Replace the example URL and names yourself. The env file stays on the configured machine and provides `MC_API_KEY`. The collector calls the workspace HTTP API, not Convex internals; inspect `tools/mission-control.py` for its required capabilities and task schema. It refreshes read-only once per minute. No key comes back to Minecraft. Human assignments, reviews and blockers appear at the Attention desk (`J` or `/attention`). Task descriptions can contain GitHub PR URLs; `D` opens a read-only diff using your local GitHub CLI credentials. Talking to the coordinator prepares a draft; the player sends it.

## Running independently

CrewCraft does not include or connect to any maintainer’s agents, hosts, task board, saved map or chat. Your own configuration is the sole source of connections. Keep Foreman/DevBridge local; do not expose them publicly. Multiple checkouts still need distinct ports if run simultaneously. Backups and assets generated during your sessions remain local unless you deliberately share them.
