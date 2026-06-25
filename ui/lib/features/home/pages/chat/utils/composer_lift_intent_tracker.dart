import 'dart:math' as math;

/// Keeps the full-screen chat composer lifted through transient focus loss
/// while the IME is still opening or visibly present.
///
/// The latch only activates after a real input intent (focus/editing) has
/// happened. Once focus is gone, it stays active until either:
/// - the keyboard clearly starts closing, or
/// - the keyboard has fully settled closed.
class ComposerLiftIntentTracker {
  ComposerLiftIntentTracker({
    this.visibleInsetThreshold = 0.5,
    this.motionEpsilon = 1.0,
    this.openingGraceFrames = 2,
  });

  final double visibleInsetThreshold;
  final double motionEpsilon;
  final int openingGraceFrames;

  bool _latched = false;
  bool _imeVisibleSinceArm = false;
  double? _lastInset;
  int _openingGraceFramesRemaining = 0;

  void arm() {
    _latched = true;
    _imeVisibleSinceArm = false;
    _openingGraceFramesRemaining = openingGraceFrames;
  }

  bool update({required bool isEditing, required double bottomInset}) {
    final inset = bottomInset.isFinite ? math.max(0.0, bottomInset) : 0.0;
    final keyboardVisible = inset > visibleInsetThreshold;

    if (isEditing && !_latched) {
      arm();
    }

    final lastInset = _lastInset;
    if (_latched) {
      if (keyboardVisible) {
        _imeVisibleSinceArm = true;
        _openingGraceFramesRemaining = 0;
      }

      final keyboardClearlyClosing =
          _imeVisibleSinceArm &&
          lastInset != null &&
          inset < lastInset - motionEpsilon;
      final keyboardSettledClosed =
          _imeVisibleSinceArm && inset <= visibleInsetThreshold;
      final openingExpired =
          !_imeVisibleSinceArm &&
          !isEditing &&
          inset <= visibleInsetThreshold &&
          _openingGraceFramesRemaining <= 0;

      if (keyboardClearlyClosing || keyboardSettledClosed || openingExpired) {
        _latched = false;
        _imeVisibleSinceArm = false;
        _openingGraceFramesRemaining = 0;
      } else if (!keyboardVisible &&
          !_imeVisibleSinceArm &&
          !isEditing &&
          _openingGraceFramesRemaining > 0) {
        _openingGraceFramesRemaining -= 1;
      }
    }

    _lastInset = inset;
    return isEditing || _latched;
  }
}
