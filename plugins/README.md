# Runtime Bundles

`catalog.v1.json` is the host-facing manifest for installable runtime plugins.
The Android host reads only the plugin descriptor, lifecycle adapter id, runtime
Skill location, and presentation metadata. Runtime-specific behavior stays in
the adapter selected by `adapter`.

## Add a runtime plugin

1. Add one entry to `catalog.v1.json` with a unique reverse-domain plugin id.
2. Put the packaged fallback Skill under that plugin's directory.
3. Register one `RuntimeBundleAdapter` for the manifest's `adapter` id.
4. Declare localized descriptions, capabilities, usage guidance, and optional
   navigation actions in `presentation`.
   Use `profiles` only when a packaged plugin is intentionally limited to a
   build profile; entries without it are available in both `main` and
   `investor` builds.
5. Publish a complete Skill with the same `runtimeSkill.id` in the official
   Skills repository when the runtime should support independent updates. A
   market runtime must contain `scripts/runtime/python/omniflow/bridge.py` and
   `scripts/runtime/.runtime/installed.json`; incomplete entries are ignored.

Plugins may expose a direct management surface through
`presentation.dashboard`. The market uses this action for its quick-entry
button and the detail page prefers it over the legacy `installedAction`.

Install atomically downloads the catalog's versioned component ZIP, verifies its
pinned SHA-256, registers the contribution, and enables it. The platform does
not persist the installed state until all of those steps succeed. Update fetches
the newly catalogued component without reinstalling the APK and switches only
to a complete verified market runtime. The normal `main`
profile contains no packaged runtime and never falls back to APK assets. The
`investor` profile may retain a packaged offline fallback. Uninstall disables
official Skills and reclaims their downloaded runtime data.

## OmniFlow automation bundle

XiaoWan's native Android GUI runtime and Kotlin online `vlm_task` ship with the
APK and do not require Python or plugin installation. The optional OmniFlow
bundle adds manual recording, canonical RunLog-to-Function conversion,
Function recall and parameter binding, and OmniTransfer replay. Installing the
bundle prepares and verifies its Skill backend, including Mobilerun, Python,
NumPy, and the canonical OmniTransfer runtime. Model files remain provider-side
and are not bundled into the APK.

## Vibe Builder and generated plugins

`com.omnimind.vibe-project-builder` is a hidden core runtime plugin backed by a
packaged Skill and the generic `sandbox_bundle` adapter. It contributes no
capability until installed and enabled.

The Skill lets the Agent build or import a complete workspace directory using
the normal file and terminal tools. `plugin.pool.check` verifies that directory;
`plugin.pool.publish` atomically links it as an independent
`local.project.<slug>` plugin. Re-publishing updates its Xiaowan Skill, tools,
connectors, and optional frontend while preserving connector-owned data.

Each generated plugin is Skill-first: `skill/SKILL.md` is installed into
Xiaowan's normal Skill index, while `toolkit.json` publishes business operations
into the existing Agent toolbox through the generic plugin `ToolHandler`.
Named connectors bind those tools to Xiaowan, optional SQLite storage, and
future HTTP, MCP, device, or automation runtimes. SQLite is not required and is
never the plugin abstraction itself.

The host appends the generated, namespaced tool catalog to the installed Skill,
so Xiaowan knows the exact callable tool names without hard-coding them in the
project source. Plugin enable, disable, update, and uninstall synchronize the
Skill lifecycle with the toolbox lifecycle.

The Linker also injects a small `window.omni` Bridge into the installed copy.
Dashboard business actions call `window.omni.tools.call` with local names from
`toolkit.json`, so chat and the frontend execute the same Connector contract
without implementing HTTP routes or model credentials. Generated JavaScript
cannot call another plugin's tools or database, and AI provider secrets remain
behind the native runtime.

Plugin code and durable data have separate lifecycles. Published Skill,
toolkit, optional frontend/schema, manifest, and Bridge files live under
`plugin-pool/user`, while each optional SQLite database lives under
`plugin-data/<plugin-id>/project.db`.
Updating or uninstalling plugin code preserves the database; publishing the
same plugin id reconnects it to that existing data. Legacy databases stored
inside `.omni/data` migrate automatically on first access.

## Build profiles

The repository maintains one host implementation and two packaging profiles:

- `./gradlew assembleDevelopStandardDebug -POMNIBOT_PROFILE=main
  -Ptarget=lib/main_standard.dart` builds the normal `main` profile. It exposes
  the OmniFlow catalog entry but installs it only on demand from the plugin
  market; no OmniFlow or investor runtime payload is embedded in the APK.
- `./gradlew assembleDevelopStandardDebug -POMNIBOT_PROFILE=investor
  -Ptarget=lib/main_standard.dart` builds the `investor` profile. It keeps
  the complete packaged plugin catalog and enables all official demo plugins
  on first launch.

Both profiles use the same Kotlin, Flutter, plugin contracts, and catalog. The
profile controls only packaged assets, catalog visibility, and first-launch
defaults, so fixes stay on one branch and are tested against the same host.
