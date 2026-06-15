# OOB Project Sandbox Market Plan

## Decision

Do not merge the old `wzw-dev` Workbench implementation directly.

That branch contains a complete Project/Workbench runtime, but it is far behind `main` and mixes UI mode changes, hot reload, agent tools, backend runtime, tests, docs, and unrelated generated artifacts. Pulling it into `main` would create a large regression surface.

Merge a scaffold first.

## Minimal Main-Branch Scope

The smallest useful version is a Project sandbox market contract:

1. A built-in `oob-project-sandbox` skill.
2. A component package manifest.
3. A market index manifest.
4. A validator that blocks user-data upload.
5. Example package and docs.

This makes Project mode useful as a sharing format before the app has a full marketplace UI.

## Product Shape

The shared unit is one Xiaowan/Omnibot component, not a full user project.

Examples:

- skill
- action card
- workflow
- widget/display surface
- prompt template plus scripts
- pet or visual asset bundle
- small configurable template app

Each component has:

- manifest metadata
- source/config/assets
- declared capabilities
- optional local data schema
- explicit no-upload data policy

## Data Rule

The market must never upload runtime user data.

Allowed in packages:

- config defaults
- schemas
- synthetic examples
- source files
- docs and public assets

Blocked:

- `data/**`
- `logs/**`
- `cache/**`
- `.env`
- keystores and signing files
- tokens/secrets
- chat history
- screenshots with private user content

## Phases

### Phase 1: Scaffold

Merge this PR.

Deliverables:

- built-in skill
- contract docs
- JSON schemas
- validator
- example package

### Phase 2: Local Sandbox Install

Add local install/import/export commands:

- package a selected component
- validate package
- install package into `/workspace/.omnibot/project-sandbox/installed/<component-id>/`
- list installed components

No remote upload yet.

### Phase 3: Market Surface

Add a UI surface for market index browsing:

- read a trusted market index
- show capabilities and permissions
- download into cache
- validate before install
- no auto-execution during install

### Phase 4: Author Upload

Add authenticated publishing only after the contract is stable:

- upload package archive and public metadata
- reject user-data paths server-side
- require manifest validation
- keep moderation/review separate from install

## Why This Is Enough

This scaffold gives teams a common artifact shape. People can start producing uploadable components now, while the runtime and UI can evolve later without rewriting the package format.
