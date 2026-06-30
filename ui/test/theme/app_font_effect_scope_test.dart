import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/theme/app_font_effect_scope.dart';

void main() {
  test('boosted non-chat font weights stay restrained', () {
    expect(AppFontEffectScope.boostedWeight(FontWeight.w300), FontWeight.w400);
    expect(AppFontEffectScope.boostedWeight(FontWeight.w400), FontWeight.w500);
    expect(AppFontEffectScope.boostedWeight(FontWeight.w500), FontWeight.w600);
    expect(AppFontEffectScope.boostedWeight(FontWeight.w600), FontWeight.w600);
    expect(AppFontEffectScope.boostedWeight(FontWeight.w700), FontWeight.w700);
  });
}
