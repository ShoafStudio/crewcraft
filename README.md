# CrewCraft

**Your agents. Your world.**

A Minecraft workspace for your own AI team: persistent characters, live text monitors, multi-agent conversations, and an in-game construction planner.

CrewCraft is an independent MIT-licensed derivative of [AgentCraft](https://github.com/blendi-remade/agentcraft). The original project, artwork, Minecraft integration, and Claude orchestration foundation are credited to its contributors. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Bring your own agents

A fresh installation has **no configured agents, SSH hosts, credentials, pinned chats, task board, or remote connections**. It does not include a maintainer’s bots, private world, conversations, projects, or Git history. You configure your own existing Hermes, OpenClaw, or local Codex runtimes. They continue running on their original computers.

- One permanent character per configured main agent; runtime team members appear separately.
- Working agents use computers; idle agents use separate lounge seats.
- Identity-bound monitors show visible output and activity, not private reasoning or remote desktop video.
- Conversations route to a selected agent or your explicitly configured coordinator.
- Builder researches references, generates concepts through your Codex installation, prepares previews, and submits validated block plans to game-owned construction NPCs.
- Optional task-board and task-linked GitHub PR readers use your own configuration and access.

## Quick start

Requirements: **Node.js 22+**, **JDK 25**, Python 3, and Git. This repository targets Fabric/Minecraft **26.3**. Minecraft and Foreman run locally. Model-backed features require your own installed, authenticated runtime and may incur that provider’s usage charges. Chrome is needed for Builder’s reference screenshots and design previews.

```sh
git clone https://github.com/ShoafStudio/crewcraft.git
cd crewcraft
npm ci --prefix foreman
npm ci --prefix tools
node tools/setup-crewcraft.mjs
```

On macOS, double-click **Launch CrewCraft.command**. Or on macOS/Linux:

```sh
node tools/unix.mjs launch --backend observer --profile crewcraft --home "$PWD/.crewcraft-home" --foreman-arg --no-notify
```

Stop with `node tools/unix.mjs stop --profile crewcraft`, or **Stop CrewCraft.command** on macOS.

The wizard creates your first agent’s private configuration without connecting or sending messages. Messaging defaults to off. Edit `.crewcraft-home/machines.json` and `characters.json` to add more agents; see [setup and operating guide](HEADQUARTERS.md). If you skip setup, observer mode starts unconfigured and does not collect machine inventories.

## Build and customize

Talk to **Builder**, or use `/build office` for a preset proposal based on your configured roster, then `/build preview` and `/build build`. Custom plans, concept art, and supplied Sponge v2/v3 `.schem` imports are supported. Generated art is a design reference, not an exact schematic. Construction validates bounds, supported blocks, ownership, and monitor bindings; it supports pause, resume, and undo.

The default world is the original studio. Private custom maps and a maintainer’s furnished tower are **not included**. Use Builder to create your own world. The preset has bounded team capacity; larger rosters need a custom layout.

## Development and limitations

[Contributor guide](AGENTS.md) · [Coordinator context](docs/COORDINATOR.md) · [Foreman](foreman/README.md) · [Tools](tools/README.md)

```sh
npm run typecheck --prefix foreman
npm test --prefix foreman
PYTHONPATH=tools python3 -m unittest tools/test_observe.py tools/test_message_agent.py tools/test_mission_control.py
JAVA_HOME=/path/to/jdk-25 GRADLE_USER_HOME="$PWD/.gradle-home" sh mod/gradlew -p mod build
```

Runtime status is inferred from bounded records and may be unknown or stale. Monitoring is read-only; sending a message can invoke the selected runtime’s real tools. Uncertain messages are not automatically retried. Approvals and runtime lifecycle controls stay in the original runtime. The optional task-board adapter expects the documented workspace API shape; it is not a generic connection to any Convex deployment. The PR reader does not merge PRs.

The upstream simulated and Claude-orchestration backends remain separate advanced modes. The launch instructions above select observer mode; they do not start an automatic coding team.

Release validation: the mod build, TypeScript check, Python adapters, and observer/construction tests pass. Two fake-SDK integration tests for the optional Claude orchestration mode currently time out on the release machine; that mode is not fully verified.
