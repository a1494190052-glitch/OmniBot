import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/composer_lift_intent_tracker.dart';

void main() {
  test('keeps lift latched through transient focus loss while IME opens', () {
    final tracker = ComposerLiftIntentTracker();

    tracker.arm();

    expect(tracker.update(isEditing: false, bottomInset: 0), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 186), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 301), isTrue);
  });

  test(
    'keeps lift latched while IME remains visibly stable after focus loss',
    () {
      final tracker = ComposerLiftIntentTracker();

      tracker.arm();

      expect(tracker.update(isEditing: false, bottomInset: 312), isTrue);
      expect(tracker.update(isEditing: false, bottomInset: 312), isTrue);
      expect(tracker.update(isEditing: false, bottomInset: 311.4), isTrue);
    },
  );

  test('releases latch once keyboard clearly starts closing', () {
    final tracker = ComposerLiftIntentTracker();

    tracker.arm();

    expect(tracker.update(isEditing: false, bottomInset: 320), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 320), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 304), isFalse);
  });

  test('does not lift a visible keyboard without prior input intent', () {
    final tracker = ComposerLiftIntentTracker();

    expect(tracker.update(isEditing: false, bottomInset: 288), isFalse);
  });

  test('clears the latch after the keyboard settles closed', () {
    final tracker = ComposerLiftIntentTracker();

    tracker.arm();

    expect(tracker.update(isEditing: false, bottomInset: 260), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 0), isFalse);
    expect(tracker.update(isEditing: false, bottomInset: 280), isFalse);
  });

  test('releases a pending arm after the keyboard never becomes visible', () {
    final tracker = ComposerLiftIntentTracker();

    tracker.arm();

    expect(tracker.update(isEditing: false, bottomInset: 0), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 0), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 0), isFalse);
  });

  test('editing state keeps lift armed without focus snapshot input', () {
    final tracker = ComposerLiftIntentTracker();

    expect(tracker.update(isEditing: true, bottomInset: 0), isTrue);
    expect(tracker.update(isEditing: true, bottomInset: 320), isTrue);
    expect(tracker.update(isEditing: false, bottomInset: 320), isTrue);
  });
}
