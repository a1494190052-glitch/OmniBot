---
name: oob-project-sandbox
description: Package, review, publish, or install a sandboxed Omnibot Project component. Use when the user asks to upload/download/share a Xiaowan component, publish an app to a Project market, turn an Omnibot UI/workflow/skill into a configurable component, or create a no-user-data component package.
---

# OOB Project Sandbox

Use this skill when a user wants to turn one Xiaowan/Omnibot component into a shareable Project package, or wants to install a package from a Project sandbox market.

This is a scaffold skill. It defines the package contract and review flow only. Do not assume a full Workbench runtime, UI market, account system, or remote upload service exists.

## Product Boundary

The unit of sharing is one component, not a full user project.

Good package candidates:

- one skill
- one configurable widget or display surface
- one workflow/action card
- one prompt template plus scripts
- one pet/component asset bundle
- one small template app whose state is local-only

Do not package:

- user data
- chat history
- runtime logs
- credentials, API keys, tokens, cookies, keystores, or `.env` files
- generated cache/build output
- a full repository dump

## Required References

Read only what is needed:

- `references/component-package-contract.md` for package manifest and file layout
- `references/market-sandbox-policy.md` for upload/download, data, and review rules

Use `scripts/validate_component_package.py` before recommending upload or install.

## Package Flow

1. Identify the single component boundary.
2. Classify it as `skill`, `widget`, `workflow`, `template_app`, `asset_bundle`, or `display`.
3. Create `oob_project_component_manifest.v1.json`.
4. Include only source/config/assets needed to run the component.
5. Exclude all data paths listed in `market-sandbox-policy.md`.
6. Run the validator.
7. If valid, produce a package archive or market entry.

Default package path:

```text
/workspace/.omnibot/project-sandbox/packages/<component-id>/
```

Default install path:

```text
/workspace/.omnibot/project-sandbox/installed/<component-id>/
```

## Market Flow

For upload:

1. Validate the component manifest.
2. Verify `dataPolicy.uploadsUserData` is `false`.
3. Verify the file list does not include blocked paths.
4. Create or update a market index entry.
5. Upload only the package archive and public metadata.

For download:

1. Read the market entry.
2. Download the manifest/package into the sandbox cache.
3. Validate before install.
4. Show capabilities and permissions to the user.
5. Install into the sandbox path. Do not execute code during install.

## Data Rule

Never upload user data. A component may define a local data schema, but the package must not contain real records.

Allowed:

- `config/defaults.json`
- `schema/*.json`
- `README.md`
- source files
- sample data only when clearly synthetic and under `examples/`

Blocked:

- `data/**`
- `logs/**`
- `cache/**`
- `.env`
- `*.jks`
- `*.keystore`
- conversation exports
- screenshot captures containing user content

## Output Shape

When finishing a package/review task, report:

```text
Project Sandbox Package
- Component: <id> <version>
- Type: <type>
- Files: <count>
- Data upload: none
- Capabilities: <list>
- Validation: PASS|FAIL
- Next step: upload|install|fix
```

If validation fails, do not upload or install. Explain the failing field/path and the smallest fix.
