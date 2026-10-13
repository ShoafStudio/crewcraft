# CrewCraft contributor guide

CrewCraft is an independent MIT-licensed derivative of AgentCraft. Preserve LICENSE, NOTICE and upstream attribution. Read README.md, HEADQUARTERS.md and docs/COORDINATOR.md.

Use generic configuration-driven identities. Never add developer-specific names, addresses, accounts, pinned chats, tokens, project paths or task-board endpoints as defaults. A fresh observer installation must not scan machines or connect to services until configured. Sessions are not managers. Builder and its native construction crew are separate from runtime agents.

Keep secrets, configuration, logs, inventory, messages, generated assets and Minecraft saves in ignored local directories. Do not publish hidden reasoning or tool arguments on monitor feeds. Remote agents stay on their own machines. Do not replace credentials, change runtime models, restart remote gateways or retry uncertain messages to work around failures.

Use isolated branches/checkouts for concurrent work. Validate TypeScript with `npm run typecheck --prefix foreman`, run relevant Vitest suites and `PYTHONPATH=tools python3 -m unittest tools/test_observe.py tools/test_message_agent.py tools/test_mission_control.py`, and build the mod with JDK 25 and project-local GRADLE_USER_HOME. Node 22+ and Fabric/Minecraft 26.3 are required.

Back up the stopped world before layout changes. Use bounded `dev.builder` validation and execution, preserve player edits/block entities, and verify native monitor bindings and world state afterward. Never replace validation with arbitrary model-generated Minecraft commands.
