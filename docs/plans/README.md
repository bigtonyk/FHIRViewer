# Implementation plans

One plan per feature area, plus the material it superseded. Read the plan for a feature
before changing it; each records the design decisions behind the code.

| Path | State |
|------|-------|
| `workspace/` | Current — workspace persistence, recovery snapshots and startup restoration. |
| `scriptingrefined/` | Current — the scripting console, the script APIs and SMART reuse, phase by phase. Automated testing (Phase 3) is planned but not implemented: its engine was removed in `7724874` because nothing could start it. |
| `scriptingautomation/` | Current companion roadmap — batch workflows, interoperability automation and ecosystem work beyond the console. |
| `FHIRViewer_Feature_Roadmap_and_Planning_Backlog.md` | Idea-level backlog, not a plan for any one feature. |
| `archived/scripting/` | Superseded by `scriptingrefined/`. Kept for the history of the phase split, not updated. |
| `archived/server-plugin/` | Superseded server/REST plans, including the two server-plugin plans that used to sit in the repository root. |
| `archived/` (other files) | Superseded REST and server plan files, numbered `00_`–`11_`. |

## Scripting: which tree is authoritative

`scripting/` and `scriptingrefined/` carry the same six phase filenames, which is what made
the pair ambiguous. Only `scriptingrefined/` is authoritative: it is the later, larger plan
(status 2026-10-23 against 2026-10-20) and it is the one that describes both the interactive
console and saved workflows. The archived copy is a record of the first intake, and a reader
who needs the phase numbering should take it from `scriptingrefined/`.

`scriptingautomation/` uses different filenames (`IMPLEMENTATION_STATUS.md`, `phases/`) on
purpose: it is a separate, later roadmap for automation work that sits on top of the console,
not another version of the refined plan.
