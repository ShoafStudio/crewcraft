# Visual bar — AgentCraft

The bar is **"a screenshot of this would get thousands of upvotes on r/Minecraft and look like a
real product."** Every visual change must be verified by an in-game screenshot (via the DevBridge)
and critiqued against this document. "It renders" is not done. "It looks intentional, cohesive,
and beautiful at every one of the QA camera angles" is done.

## Art direction: "Warm Studio"

A calm, high-craft creative studio — think a Scandinavian design office crossed with a cozy
observatory. Not cyberpunk neon, not generic sci-fi.

- **Materials**: pale birch & stripped oak floors, cream/white plaster (calcite, white concrete,
  smooth quartz), warm terracotta accents, brass/copper fittings (cut copper, waxed — never
  oxidized green unless deliberate), dark walnut (dark oak) furniture, lots of glass and plants
  (azalea, moss, hanging vines, potted ferns), soft carpets.
- **Palette** (custom textures/UI must use these):
  - Cream `#F4EFE6`, Paper `#E9E1D3`, Clay `#D97757`, Clay-dark `#B4553A`,
    Walnut `#3B2A20`, Brass `#C9A227`, Sage `#8FA98B`, Ink `#1F1E1D`, Signal-teal `#2FA3A0`.
  - Status colors: thinking `#C9A227`, working `#2FA3A0`, waiting-on-user `#D97757` (pulsing),
    blocked/error `#C2413B`, done `#8FA98B`, idle `#9C9488`.
- **Light**: warm. Interior lit by hidden light sources (lights behind trapdoors, under carpets via
  light blocks), lanterns, and the glow of screens. Golden-hour time of day for hero shots.
  No dark corners, no flat over-lit fullbright look either.
- **Screens/UI**: matte paper-like panels with ink text, Clay highlights, generous padding,
  thin brass borders, no default-Minecraft gray GUI anywhere in our screens. Text crisp and legible
  at normal viewing distance. Monitors glow softly (emissive), with subtle scanline/bezel framing.

## Characters

Each agent is instantly distinguishable by silhouette + color: distinct hair/hat/outfit, color-
coded scarf/badge matching the agent color. Hand-crafted 64×64 skins in the palette (no noisy
AI-pixel mush — clean, intentional pixel art with shading ramps of 3–4 tones). Nameplate:
name + small colored state dot + activity text ("editing auth.ts"). Agents look *alive*: walk to
stations, sit/stand at desks, turn heads toward the user when speaking, idle animations, small
particles reflecting state (gentle sparkles when thinking, a pulsing clay "!" above head when
waiting on the user).

## Composition rules

- Every room must read clearly from the doorway: one focal point, clear paths, 3-block+ ceilings
  with ceiling detail (beams, lights), no bare flat walls longer than 5 blocks without articulation
  (pillars, windows, shelves, frames), floors with border patterns.
- Exterior: the HQ should look like architecture, not a box — pitched or stepped roofline, big
  windows, terraces, landscaping (trees, paths, water feature), sitting on a pleasant
  island/meadow, not a void.
- Information at three distances: far (beacon/lamp colors = status at a glance), mid (task wall
  cards, nameplates), near (monitor text, diffs).

## QA camera set (tools/scenes/qa.json) — every release must look great in all of these

1. Exterior hero (golden hour, 3/4 view of the HQ)
2. Entrance looking into the Goal Atrium
3. Task Wall straight-on, populated with ~10 cards across columns
4. Agent desk close-up: agent at desk, monitor streaming log legible
5. Wide interior: 3+ agents working at different stations
6. Decision podium with an open decision + agent waiting near the user
7. Console screen open (command input with autocomplete)
8. Diff review screen open with a real multi-file diff
9. Library / memory screen open
10. Night shot (lit interior through windows)

## Review rubric (score 1–10 each; ship only if all ≥ 8)

Cohesion with art direction · Composition & readability · Lighting · Character appeal ·
Text/UI legibility & polish · "Alive-ness" (motion, particles, state feedback) · Practical clarity
(could a user understand the state of work in 5 seconds?).
