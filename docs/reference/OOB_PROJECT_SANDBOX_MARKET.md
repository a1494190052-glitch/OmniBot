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

### Phase 2A: Local Frontend/Backend Server

This branch adds a local server under `tools/project-market-server/`.

It provides:

- static web UI at `http://127.0.0.1:17331`
- local HTTP API for create/validate/import/list/detail/edit/rebuild/clone/remove/download
- built-in server HTTP API for upload/download/list/archive
- disk-backed market storage
- separate built-in server storage
- local zip archive build
- no arbitrary package execution
- no user-data upload

Default storage:

```text
~/.omnibot/project-market-server/
```

Default built-in server storage:

```text
~/.omnibot/project-market-server/builtin-server/
```

This answers the first implementation question: yes, Project can have a small frontend/backend first. The backend can store existing Project component packages as validated archives and a market index. The initial server is local-first, but the API shape can be reused for a remote authenticated service later.

The local management surface supports:

- create a configurable component draft
- import an existing component manifest
- edit package metadata
- edit the declared entry config file
- view package files and hashes
- rebuild the downloadable archive
- clone a component to a new version
- delete local stored packages
- upload a local sandbox app to built-in server storage
- download a built-in server sandbox app back to local editable storage

Every edit path re-runs validation and rolls back on failure.
Every upload/download path re-runs validation before updating the target market index.

Local compile should start as package build:

1. validate manifest
2. copy declared files only
3. block data/secrets/logs/cache
4. build a zip archive
5. update market index

Do not compile arbitrary uploaded code, Android plugins, or APKs until sandboxing, signing, review, and execution permissions are defined.

The built-in server boundary is intentionally simple:

1. local editable sandbox store
2. built-in server market store
3. explicit upload from local to server
4. explicit download from server to local
5. zip archives served from both stores

This gives the product a real upload/download loop now, while keeping future authenticated remote storage interchangeable.

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
