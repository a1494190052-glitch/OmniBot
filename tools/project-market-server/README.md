# Omnibot Project Market Server

Local frontend/backend for the OOB Project sandbox market.

This server stores validated Project component packages on disk, serves a small web UI, exposes a JSON API, and builds downloadable zip archives. It is intentionally local-first: packages may include configuration, public assets, schemas, docs, and component source, but must not include user data, runtime logs, caches, credentials, or secrets.

## Run

```bash
cd tools/project-market-server
npm start
```

Default URL:

```text
http://127.0.0.1:17331
```

Options:

```bash
npm start -- --host 127.0.0.1 --port 17331 --store ~/.omnibot/project-market-server
```

## API

- `GET /api/health`
- `GET /api/projects`
- `GET /api/market`
- `POST /api/validate`
- `POST /api/projects/create`
- `POST /api/projects/import`
- `GET /api/projects/<componentId>/<version>`
- `POST /api/projects/update`
- `POST /api/projects/rebuild`
- `POST /api/projects/clone`
- `POST /api/projects/remove`
- `GET /api/projects/<componentId>/<version>/archive`

`POST /api/projects/import` accepts:

```json
{
  "manifestPath": "/absolute/path/to/oob_project_component_manifest.v1.json",
  "packageRoot": "/absolute/path/to/package-root"
}
```

`packageRoot` defaults to the manifest directory.

`POST /api/projects/create` creates a new local configurable component package:

```json
{
  "componentId": "daily-review-card",
  "name": "Daily Review Card",
  "version": "0.1.0",
  "type": "widget",
  "description": "A configurable Xiaowan component.",
  "capabilities": "local_config, prompt_template",
  "component": {
    "title": "Daily Review",
    "prompt": "Summarize today's important work."
  }
}
```

`POST /api/projects/update` edits stored package metadata and entry content, then validates and rebuilds:

```json
{
  "componentId": "daily-review-card",
  "version": "0.1.0",
  "manifestUpdates": {
    "name": "Daily Review Card",
    "description": "Updated description",
    "type": "widget",
    "capabilities": "local_config, prompt_template",
    "configuration": {
      "schema": {
        "type": "object"
      }
    }
  },
  "entryContent": "{\"title\":\"Daily Review\"}"
}
```

If validation fails, the edit is rolled back.

## Local Build

Importing a package also builds the local archive:

1. Run the Project sandbox validator.
2. Copy only files declared by the manifest.
3. Add the manifest file.
4. Build a zip archive in the local store.
5. Update the local market index.

This is a package build, not arbitrary code execution and not APK/plugin compilation.

## Management Features

The local UI supports:

- create a new configurable Project component
- import an existing component manifest
- browse stored Project components
- inspect manifest details and package files
- edit name, type, description, capabilities, configuration JSON, and entry config
- save and rebuild the package archive
- clone a component to a new version
- remove a stored component
- download the generated zip archive

All write paths re-run the Project sandbox validator before the market index is updated.

## Storage

Default storage root:

```text
~/.omnibot/project-market-server/
```

Layout:

```text
market.json
packages/<component-id>/<version>/
archives/<component-id>-<version>.zip
```

The market index can later be backed by an authenticated server or object storage. The package validation rules should remain the same on both local and remote storage.
