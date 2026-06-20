import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/memory/pages/memory_center/memory_center_page.dart';

void main() {
  test(
    'Memory center initial tab resolver opens reusable commands aliases',
    () {
      expect(
        resolveMemoryCenterInitialTabIndex('reusable_commands'),
        memoryCenterReusableCommandsTabIndex,
      );
      expect(
        resolveMemoryCenterInitialTabIndex('reusable-commands'),
        memoryCenterReusableCommandsTabIndex,
      );
      expect(
        resolveMemoryCenterInitialTabIndex('functions'),
        memoryCenterReusableCommandsTabIndex,
      );
      expect(
        resolveMemoryCenterInitialTabIndex('reusable_memory'),
        memoryCenterReusableCommandsTabIndex,
      );
      expect(
        resolveMemoryCenterInitialTabIndex('复用指令'),
        memoryCenterReusableCommandsTabIndex,
      );
      expect(
        resolveMemoryCenterInitialTabIndex('复用记忆'),
        memoryCenterReusableCommandsTabIndex,
      );
    },
  );

  test('Memory center initial tab resolver keeps memory defaults stable', () {
    expect(
      resolveMemoryCenterInitialTabIndex('long_term'),
      memoryCenterLongTermTabIndex,
    );
    expect(
      resolveMemoryCenterInitialTabIndex('cloud'),
      memoryCenterLongTermTabIndex,
    );
    expect(
      resolveMemoryCenterInitialTabIndex(null),
      memoryCenterShortTermTabIndex,
    );
    expect(
      resolveMemoryCenterInitialTabIndex('unknown'),
      memoryCenterShortTermTabIndex,
    );
  });
}
