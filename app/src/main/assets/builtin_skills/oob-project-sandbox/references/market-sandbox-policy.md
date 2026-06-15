# Project Sandbox Market Policy

The Project sandbox market is a controlled exchange for Omnibot component packages.

It is not a sync service for user data.

## Upload Policy

Only upload:

- component manifest
- source/config files
- public assets
- docs
- synthetic examples

Never upload:

- user data
- logs
- caches
- chat history
- screenshots with user content
- credentials
- signing files
- private workspace files unrelated to the component

## Download Policy

Downloaded packages must be treated as untrusted until validation passes.

Install process:

1. Download into a sandbox cache.
2. Validate manifest and file list.
3. Show capabilities and permissions.
4. Copy files into the install sandbox.
5. Do not auto-run scripts or generated code during install.

## Sandbox Paths

Use these workspace paths:

```text
/workspace/.omnibot/project-sandbox/cache/
/workspace/.omnibot/project-sandbox/packages/
/workspace/.omnibot/project-sandbox/installed/
/workspace/.omnibot/project-sandbox/market/
```

Packages must not write outside `/workspace/.omnibot/project-sandbox/` unless a later runtime explicitly asks the user and receives confirmation.

## Data Policy

`dataPolicy.uploadsUserData` and `dataPolicy.uploadsRuntimeData` must both be `false`.

A component may declare `storesDataLocally: true` only when it creates local user state after installation. That local state is not part of the published package.

## Blocked Path Patterns

Packages fail review if any listed file matches:

```text
data/**
logs/**
cache/**
captures/**
screenshots/**
.env
.env.*
*.jks
*.keystore
*.p12
*.pem
*.key
*secret*
*token*
```

## Capability Review

Capabilities should describe what the installed component can do. Keep names stable and narrow.

Examples:

- `local_config`
- `skill_runtime`
- `html_display`
- `flutter_widget`
- `prompt_template`
- `workspace_file_read`
- `android_intent`
- `notification`

If a component needs network access, Android automation, file writes outside its sandbox, or privileged commands, mark it as requiring explicit user approval. Do not hide those permissions in docs.

## Minimal Main-Branch Scope

The first merge should include only:

- built-in skill
- package contract
- validator
- examples
- tests/docs proving data is excluded

Do not merge the old Workbench runtime, UI mode, hot reload, or backend executor in this step.
