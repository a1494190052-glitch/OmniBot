---
name: omniflow-gui-runtime
description: Install the pinned OmniFlow GUI runtime used by the Omni VLM plugin.
compatibility: Android arm64-v8a with the Omnibot Alpine runtime
metadata:
  owner: omnimind
  runtime: omniflow
---

# OmniFlow GUI Runtime

This Skill is managed by the Omni VLM plugin. The plugin invokes
`scripts/bootstrap_runtime.py` to install and verify the pinned OmniFlow,
OmniTransfer, NumPy, and JSON Repair runtime outside the APK.
