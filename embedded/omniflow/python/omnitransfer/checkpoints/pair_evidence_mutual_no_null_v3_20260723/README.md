# Pair-Evidence Mutual Matcher v3

This checkpoint removes the learned NULL row, column, head, and inference
class. Mutual assignment ranks concrete source-target pairs. The unnormalized
pair affinity is trained with balanced binary supervision and provides the
absolute confidence used by the fail-closed runtime gate.

- PyTorch schema: `omnitransfer_mutual_matcher_v3`
- NumPy schema: `omnitransfer_numpy_mutual_matcher_v2`
- PyTorch SHA256: `61beec6da26f7aab7c51fd778ea22b5cfc956ca0cb658f1e91f4e8debc6f95b8`
- NumPy SHA256: `6e5668343419da38776e1f32ad9da610abc323637d8f6c6df38fb72ddec062b8`
- Frozen test ranking Top-1: `0.7744974874`
- Frozen test Recall@5: `0.9246231156`
- Coverage at confidence 0.5: `0.8291457286`
- Selective accuracy at confidence 0.5: `0.8553030303`
- Parameters: `670362`

`OMNITRANSFER_MATCHER_MIN_PROBABILITY` overrides the absolute pair-confidence
gate. `OMNITRANSFER_MATCHER_MIN_MARGIN` overrides the relative top-1 margin
gate. A failed gate returns transfer failure so the caller can use its normal
VLM fallback; source coordinates are never replayed directly.
