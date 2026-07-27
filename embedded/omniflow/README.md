# OmniFlow Android Runtime

OpenOmniBot builds this directory into one versioned Python bundle. The bundle
contains OmniFlow, the canonical OmniTransfer runtime, the shared OOB schemas,
the pinned ARM64 musllinux NumPy wheel, and the pinned pure-Python json_repair
wheel used only at the native tool-argument parsing boundary.

The phone never installs OmniFlow or OmniTransfer with pip. On first use the
Android adapter verifies and atomically extracts the bundle under
`/workspace/.omnibot/runtime/omniflow`. If Alpine does not yet contain Python,
the adapter installs only `python3` and `libstdc++`; the general Node/npm/git
terminal bootstrap is not required.

The embedded Python runtime currently supports `arm64-v8a` devices only,
matching OpenOmniBot's embedded Alpine runtime support.

The snapshot contains the OmniFlow modules imported by the Android bridge and
the six-module canonical OmniTransfer replay closure. Offline evaluation,
training, dataset, and benchmark utilities are intentionally excluded from the
phone bundle.

Runtime versions live in `runtime.properties`; exact source hashes are generated
into every APK. `oob_omniflow_bridge.py` reads that generated identity instead
of carrying another hard-coded copy.

The canonical OmniTransfer checkpoint is exported to a pickle-free NumPy archive
for Android. The NumPy backend executes the same trained mutual-assignment model
for XML graphs; PyTorch remains the training and screenshot-inference backend.
Runtime health performs one real mapping before Android marks the process ready.
Transfer still fails closed and never replays source coordinates.

VLM calls use raw pixels in the current original device display frame so their
coordinates match XML bounds. `omniflow.vlm_coordinates` converts canonical
recent-action context to pixels before each call and converts validated model
pixels back to canonical `0..1000` coordinates immediately afterward. Screenshot
compression does not change this declared coordinate frame.

The source-controlled snapshots remain the reproducible default. For a fast
APK replacement without changing this directory, build directly from local
worktrees:

```bash
scripts/build-embedded-omniflow-apk.sh --local-sources
```

The normal device installer exposes the same zero-copy development path:

```bash
bash scripts/install-dev.sh --device SERIAL --local-sources --allow-dirty-runtime
```

This packages the canonical sibling worktrees directly. A repository symlink is
not used because it would break standalone clones, CI, and reproducible Android
asset packaging.

Dirty worktrees are rejected unless the caller explicitly adds
`--allow-dirty`. The same behavior is available without the wrapper through
`OOB_OMNIFLOW_SOURCE_DIR`, `OOB_OMNITRANSFER_SOURCE_DIR`, and
`OOB_ALLOW_DIRTY_RUNTIME_SOURCES=1` Gradle properties or environment variables.
The wrapper runs the Android bridge contract before Gradle, so incompatible
OmniFlow or OmniTransfer API changes fail quickly instead of producing an APK
that only fails after installation.

Install and validate the actual embedded runtime on an ARM64 device with:

```bash
scripts/demo-embedded-omniflow-apk.sh --device SERIAL --build
```

Add `--local-sources --allow-dirty` to that command when intentionally testing
the current local OmniFlow and canonical OmniTransfer worktrees.

The runtime interface is JSON Lines over stdin/stdout. The sole API source is
`schemas/oob/omniflow_android_bridge.v2.json`; the Python Bridge, generated APK
manifest, Android runtime checks, host verifier, and device demo all derive the
protocol and closed operation set from that contract.
