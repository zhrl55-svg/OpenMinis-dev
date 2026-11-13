<!-- >>> codebase-index managed >>> -->
# codebase-index

Use the local codebase index before scanning repository files.

Skill resources: `.codex/skills/codebase-index/SKILL.md`

Run `codebase-index search "<query>" --session <tag> --json` for general
questions, or use `symbol`, `refs`, `impact`, and `graph` for symbol lookup,
references, change impact, and HTML graph export. Use one session tag per
conversation: unchanged evidence you already received comes back as
`reused: true`, and evidence that changed is listed under `memory.invalidated`.
Before relying on something read earlier, run
`codebase-index verify --session <tag> --json`. Search/read commands auto-build
the index when it is missing; run `codebase-index update` when responses report
stale data.
<!-- <<< codebase-index managed <<< -->
