# Embedded OmniFlow Runtime

This directory contains the Python runtime shipped with OpenOmniBot.

- `python/omniflow` is synchronized from `omnimind-ai/OmniFlow` commit
  `131f5331ad1e84f66381f8a9cbcbede07861a9c9`.
- `python/omnitransfer` contains the canonical replay-time dependency closure
  from `~/Projects/Omni/OmniTransfer`: `runtime.py`, `mutual_matcher.py`,
  `learned_matcher.py`, `ui_graph.py`, `schema.py`, and `__init__.py`.
- `python/oob_omniflow_bridge.py` is the small Android bridge adapter owned by
  OpenOmniBot.
- Canonical action and checker schemas are copied from this repository during
  the Android build.
- The snapshot includes only Python modules required by the Android bridge and
  runtime; training, dataset, benchmark, and offline evaluation modules are not
  bundled.
- NumPy is downloaded from PyPI at the pinned URL and verified by SHA-256.
- json_repair is downloaded from PyPI at the pinned URL and verified by SHA-256;
  its wheel carries the upstream MIT license in the bundled dist-info metadata.

`runtime.properties` pins the content hash of both embedded Python packages.
Gradle rejects source drift, so a runtime update and its provenance update
cannot be split across builds.

The canonical mutual matcher ships its frozen weights as a pickle-free NumPy
archive. Android runs the same learned XML-graph network without bundling
PyTorch; screenshot inference and training remain in the canonical repository.
No heuristic matcher or coordinate passthrough is retained in the APK.
VLM coordinate conversion is centralized in `omniflow.vlm_coordinates`; model
adapters only repair recognized argument shapes and do not infer coordinate
space from numeric magnitude.

The OmniFlow license is preserved under `LICENSES/OmniFlow-LICENSE`. The
embedded OmniTransfer snapshot is distributed as part of OpenOmniBot under the
top-level project licensing terms.
