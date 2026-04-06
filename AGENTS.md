# TripSync Server Codex Guide

Follow the repository root `AGENTS.md`, then apply the rules below for work inside `tmti_server/`.

## Local PM Routing
- For backend planning, API design, task breakdown, prioritization, or backend doc work, read `.codex/agents/pm.md` first.
- Treat that PM file as the default planning role for this subtree.
- In PM mode, do not edit backend source code unless the user explicitly asks to move into implementation.

## Git Commit Rules
- When backend work requires a commit, follow the `git-commit-convention` skill.
- Use the commit subject format `type: summary` with skill-aligned prefixes such as `feat:`, `fix:`, `chore:`, `docs:`, `ref:`, `style:`, or `test:`.
- Keep the commit message to a simple single-line subject unless the user explicitly asks for more detail.
- Avoid `git add .`; stage only the intended backend files explicitly.
- Write commit messages in Korean.

## Shared Docs
- Consult the relevant shared docs in `../docs/` before backend planning, implementation, or documentation updates.
- `../docs/PRD.md` - Product requirements, MVP scope, priorities, and core user/problem framing.
- `../docs/TECH_SPEC.md` - Technical design covering frontend, backend, data, infra, and algorithm decisions for the MVP.
- `../docs/API_SPEC.md` - REST API request/response contracts, validation rules, and error handling details.
- `../docs/DB_SCHEMA.md` - MySQL schema, constraints, indexes, lifecycle, and deletion policy definitions.
- `../docs/CONSENSUS_ENGINE.md` - Consensus engine rules for conflict analysis, slot allocation, place selection, satisfaction scoring, and LLM boundaries.
- `../docs/TEST_PLAN.md` - Quality strategy, test scope, acceptance criteria, and verification targets.
- `../docs/proposal_draft.md` - Service proposal narrative covering background, necessity, and contest-facing positioning.

## Scope
- Backend application code: `src/`
- Backend schema and persistence: `prisma/`
- Backend package/tooling files in this directory
