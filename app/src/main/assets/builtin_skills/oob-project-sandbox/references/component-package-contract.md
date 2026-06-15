# OOB Project Component Package Contract

This contract defines the smallest mergeable Project scaffold: a component package that can be validated, published to a market index, downloaded, and installed into a sandbox.

It intentionally does not define a full Workbench runtime or remote marketplace backend.

## Component Manifest

Every package must include:

```text
oob_project_component_manifest.v1.json
```

Required shape:

```json
{
  "schemaVersion": "oob.project.component.v1",
  "componentId": "daily-review-card",
  "name": "Daily Review Card",
  "version": "0.1.0",
  "type": "widget",
  "description": "A configurable card for daily review prompts.",
  "author": {
    "name": "Omnibot"
  },
  "entry": {
    "kind": "config",
    "path": "component.json"
  },
  "files": [
    {
      "path": "component.json",
      "role": "config",
      "required": true
    }
  ],
  "capabilities": [
    "local_config"
  ],
  "permissions": [],
  "configuration": {
    "schema": {
      "type": "object",
      "properties": {}
    }
  },
  "dataPolicy": {
    "uploadsUserData": false,
    "uploadsRuntimeData": false,
    "storesDataLocally": false,
    "localDataSchema": null,
    "excludedPaths": [
      "data/**",
      "logs/**",
      "cache/**",
      ".env",
      "*.jks",
      "*.keystore"
    ]
  }
}
```

## Required Fields

- `schemaVersion`: must be `oob.project.component.v1`.
- `componentId`: lowercase letters, digits, and hyphens only.
- `name`: human-readable name.
- `version`: semantic version such as `0.1.0`.
- `type`: one of `skill`, `widget`, `workflow`, `template_app`, `asset_bundle`, `display`.
- `entry.path`: package-relative entry file.
- `files[].path`: package-relative file path. It must stay inside the package root.
- `dataPolicy.uploadsUserData`: must be `false`.
- `dataPolicy.uploadsRuntimeData`: must be `false`.

## File Layout

Recommended layout:

```text
<component-id>/
├── oob_project_component_manifest.v1.json
├── README.md
├── component.json
├── src/
├── config/
├── schema/
├── assets/
└── examples/
```

Use only the folders that are needed.

## Configuration Boundary

A market component should be configurable, not stateful.

Configuration examples:

- labels and display text
- field definitions
- enabled actions
- prompt snippets
- color and icon choices
- local storage schema

State examples that must not be uploaded:

- user-created records
- execution logs
- personal screenshots
- conversation history
- API responses containing private content

## Market Index Entry

The market index references packages; it does not embed runtime data.

```json
{
  "schemaVersion": "oob.project.market.v1",
  "items": [
    {
      "componentId": "daily-review-card",
      "name": "Daily Review Card",
      "version": "0.1.0",
      "type": "widget",
      "description": "A configurable card for daily review prompts.",
      "downloadUrl": "https://example.invalid/components/daily-review-card-0.1.0.zip",
      "manifestSha256": "optional-sha256",
      "capabilities": [
        "local_config"
      ],
      "dataPolicySummary": {
        "uploadsUserData": false,
        "uploadsRuntimeData": false
      }
    }
  ]
}
```

## Versioning

- Increment patch for copy/config-only updates.
- Increment minor for new optional configuration or capabilities.
- Increment major when install layout, data schema, or permissions change incompatibly.

## Review Checklist

Before publishing:

- manifest validates
- all listed files exist
- no listed file matches blocked data paths
- no secrets are present
- no runtime user records are included
- capabilities and permissions are explicit
- package can be installed without executing code
