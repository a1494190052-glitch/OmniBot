---
name: basketball-career
description: Manage the user's fictional basketball player, games, career progress, and coaching notes through direct tools.
---

# Basketball Career

Use this Skill when the user asks to create, inspect, or continue their fictional basketball career.

- Treat every game and career event as entertainment simulation, never as a real-world sports result.
- Use `create_player` only after the user has supplied a name, position, archetype, initial overall, and fan count.
- Use `record_game` to persist a completed simulation event and `list_games` to recover the career history.
- Use `career_advice` only with player facts and game summaries supplied in the call. Label its output as Xiaowan-generated coaching.
- Never invent database contents. Query before summarizing an existing career.
