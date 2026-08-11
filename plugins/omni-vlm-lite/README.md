# OmniFlow Runtime Component

This ZIP is a self-contained, versioned OmniFlow component for OpenOmniBot. The normal APK exposes
it in the plugin market and downloads the pinned package on demand, so users do not need to install
Python packages or configure paths manually.

## Contents

- `runtime-skill/omniflow-gui-runtime/`: pinned OmniFlow, canonical OmniTransfer, checkpoint, and
  Python dependencies used by the Android host.
- `schemas/oob/`: versioned bridge, Function, checker, RunLog, and action contracts.
- `agent-skill/omniflow-runtime-modifier/`: instructions for another Agent to safely edit OmniFlow
  Python and hot reload the worker.
- `INSTALL_DIR.json`: Android and shell installation paths.
- `release.json`: component version and integrity digests.

## Installation

Install or update this complete ZIP through the OpenOmniBot plugin market; do not download its
Python dependencies individually. The investor profile may package the same ZIP as an offline
fallback. See `INSTALL_DIR.json` for the resolved directory contract.

## Developer Override

Use these official Agent tools in order:

1. `get_omniflow_python_override` to inspect status or read one `omniflow/**/*.py` file.
2. `apply_omniflow_python_override` to validate, save, and hot reload a complete Python file.
3. `reload_omniflow_python_override` to restart the worker without changing source.
4. `clear_omniflow_python_override` with `confirm=true` to return to the pinned runtime.

An apply operation compiles the Python file before worker initialization and automatically restores
the previous content if reload fails. The override changes OmniFlow only. OmniTransfer remains the
canonical pinned implementation and missing mappings still fall back through the normal runtime.

Never change payment safety policy through the developer override. GUI automation may prepare an
order, but must not confirm or submit a real payment without explicit user confirmation.
