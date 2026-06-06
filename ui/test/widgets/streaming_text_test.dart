import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/streaming_text.dart';
import 'package:ui/widgets/typewriter_text.dart';

void main() {
  testWidgets('StreamingText keeps surrogate pairs intact during animation', (
    tester,
  ) async {
    const text = '前缀📎后缀';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(fullText: text, style: TextStyle(fontSize: 14)),
        ),
      ),
    );

    await tester.pump(const Duration(milliseconds: 20));
    expect(tester.takeException(), isNull);

    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    final richText = tester.widget<RichText>(
      find.byWidgetPredicate(
        (widget) =>
            widget is RichText && widget.text.toPlainText().contains('前缀'),
      ),
    );
    expect(richText.text.toPlainText(), text);
  });

  testWidgets('TypewriterText advances past emoji without splitting it', (
    tester,
  ) async {
    const text = '前缀📎后缀';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: TypewriterText(
            text: text,
            style: TextStyle(fontSize: 14),
            shouldAnimate: true,
          ),
        ),
      ),
    );

    for (var index = 0; index < text.length + 2; index += 1) {
      await tester.pump(const Duration(milliseconds: 15));
      expect(tester.takeException(), isNull);
    }

    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    final markdownBody = tester.widget<OmnibotMarkdownBody>(
      find.byType(OmnibotMarkdownBody),
    );
    expect(markdownBody.data, text);
  });

  testWidgets(
    'StreamingText resets animation state when text is replaced by a new snapshot',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              fullText: '第一版内容',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              fullText: '改写后的全新内容 😀',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      final richText = tester.widget<RichText>(
        find.byWidgetPredicate(
          (widget) =>
              widget is RichText &&
              widget.text.toPlainText().contains('改写后的全新内容'),
        ),
      );
      expect(richText.text.toPlainText(), '改写后的全新内容 😀');
    },
  );

  testWidgets(
    'StreamingText renders markdown snapshots after replacement without exceptions',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              fullText: '旧内容',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              fullText: '**新内容** 😀',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      final markdownBody = tester.widget<OmnibotMarkdownBody>(
        find.byType(OmnibotMarkdownBody),
      );
      expect(markdownBody.data, '**新内容** 😀');
    },
  );

  testWidgets('StreamingText renders streaming markdown tables safely', (
    tester,
  ) async {
    const snapshots = <String>[
      '表格如下：\n\n| 名称 | 状态 |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n| B | 待处理 |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n| B | 待处理 |\n\n后续说明',
    ];

    for (final snapshot in snapshots) {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              fullText: snapshot,
              style: const TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);
    }

    expect(find.byType(Table), findsOneWidget);
    expect(find.textContaining('后续说明'), findsOneWidget);
  });

  testWidgets(
    'OmnibotMarkdownBody only places trailingInline once when content contains both text and table',
    (tester) async {
      // 回归测试：之前 OmnibotMarkdownBody 在表格分段路径下会把同一个
      // trailingInline widget 实例注入到每个文本段对应的 MarkdownBody 中，
      // 导致同一带 key 的 widget 出现在树的多个位置，触发
      // _dependents.isEmpty / _elements.contains(element) 断言。
      const trailingKey = Key('streaming-trailing-cursor');
      const data =
          '开头说明\n\n| 列1 | 列2 |\n| --- | --- |\n| A | B |\n\n结尾说明';

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: OmnibotMarkdownBody(
              data: data,
              baseStyle: TextStyle(fontSize: 14),
              trailingInline: SizedBox(
                key: trailingKey,
                width: 4,
                height: 14,
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);

      // trailingInline 必须只挂载在树中的一个位置。
      expect(find.byKey(trailingKey), findsOneWidget);
      expect(find.byType(Table), findsOneWidget);
    },
  );

  testWidgets(
    'OmnibotMarkdownBody keeps trailingInline once when content ends with a table',
    (tester) async {
      const trailingKey = Key('streaming-trailing-cursor');
      const data = '开头说明\n\n| 列1 | 列2 |\n| --- | --- |\n| A | B |';

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: OmnibotMarkdownBody(
              data: data,
              baseStyle: TextStyle(fontSize: 14),
              trailingInline: SizedBox(
                key: trailingKey,
                width: 4,
                height: 14,
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);

      expect(find.byKey(trailingKey), findsOneWidget);
      expect(find.byType(Table), findsOneWidget);
    },
  );

  testWidgets(
    'StreamingText streams across table boundaries without Flutter assertions',
    (tester) async {
      // 全链路回归：从“无表格 → 表格出现 → 表格后追加文本”逐帧推进，
      // 模拟真实的流式渲染（同一棵树上不停 setState）。出问题时这里会抛
      // _dependents.isEmpty 或 _elements.contains(element) 断言。
      const snapshots = <String>[
        '准备开始：',
        '准备开始：\n\n| 列1 | 列2 |',
        '准备开始：\n\n| 列1 | 列2 |\n| --- | --- |',
        '准备开始：\n\n| 列1 | 列2 |\n| --- | --- |\n| A | B |',
        '准备开始：\n\n| 列1 | 列2 |\n| --- | --- |\n| A | B |\n\n结尾',
        '准备开始：\n\n| 列1 | 列2 |\n| --- | --- |\n| A | B |\n\n结尾说明继续输出',
      ];

      final harnessKey = GlobalKey<_StreamingHarnessState>();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: _StreamingHarness(key: harnessKey, snapshots: snapshots),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);

      for (var i = 1; i < snapshots.length; i++) {
        harnessKey.currentState!.advance();
        await tester.pump();
        expect(tester.takeException(), isNull);
      }

      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'StreamingText fast-path stays stable when markdown contains a table mid-stream',
    (tester) async {
      // 命中 StreamingText._buildMarkdownFastPath（mdLen < fullText.length），
      // 此时 trailingInline 会被包成 _AnimatedStreamingTail（带 ValueKey），
      // 历史 bug 会在 OmnibotMarkdownBody 表格分段路径里被复用到多个 MarkdownBody 中。
      const baseText =
          '开头说明\n\n| 列1 | 列2 |\n| --- | --- |\n| A | B |\n\n结尾说明';

      final snapshots = <String>[
        baseText.substring(0, baseText.length - 6),
        baseText.substring(0, baseText.length - 4),
        baseText.substring(0, baseText.length - 2),
        baseText,
      ];

      final harnessKey = GlobalKey<_StreamingHarnessState>();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: _StreamingHarness(
              key: harnessKey,
              snapshots: snapshots,
              // 让 fast-path 触发：每一帧的 markdownRenderedLength = 当前长度 - 2。
              markdownRenderedLengthOffset: 2,
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);

      for (var i = 1; i < snapshots.length; i++) {
        harnessKey.currentState!.advance();
        await tester.pump();
        expect(tester.takeException(), isNull);
      }

      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'StreamingText switches between plain markdown and table safely',
    (tester) async {
      const snapshots = <String>[
        '先输出普通段落',
        '先输出普通段落\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |',
        '先输出普通段落\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n\n继续输出普通段落',
        '这次又回到普通 **Markdown** 段落',
        '这次又回到普通 **Markdown** 段落\n\n| X | Y |\n| --- | --- |\n| 1 | 2 |',
      ];

      for (final snapshot in snapshots) {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: StreamingText(
                enableMarkdown: true,
                selectable: true,
                fullText: snapshot,
                style: const TextStyle(fontSize: 14),
              ),
            ),
          ),
        );
        await tester.pump();
        expect(tester.takeException(), isNull);
      }
    },
  );
}

/// 在同一棵元素树上推进流式快照的测试 harness。
///
/// 通过 [advance] 触发 setState 重建 [StreamingText]，从而模拟生产环境中
/// 父级 setState 驱动的流式渲染（而不是 [WidgetTester.pumpWidget] 每帧
/// 新建一棵树的简化场景）。
class _StreamingHarness extends StatefulWidget {
  const _StreamingHarness({
    super.key,
    required this.snapshots,
    this.markdownRenderedLengthOffset,
  });

  final List<String> snapshots;

  /// 当不为 null 时，向 [StreamingText.markdownRenderedLength] 传入
  /// `当前文本长度 - offset`，以强制命中 StreamingText 的 fast-path。
  final int? markdownRenderedLengthOffset;

  @override
  State<_StreamingHarness> createState() => _StreamingHarnessState();
}

class _StreamingHarnessState extends State<_StreamingHarness> {
  int _index = 0;

  void advance() {
    if (_index >= widget.snapshots.length - 1) return;
    setState(() => _index += 1);
  }

  @override
  Widget build(BuildContext context) {
    final text = widget.snapshots[_index];
    final offset = widget.markdownRenderedLengthOffset;
    final mdLen = offset == null ? null : (text.length - offset).clamp(0, text.length);
    return StreamingText(
      enableMarkdown: true,
      markdownRenderedLength: mdLen,
      fullText: text,
      trailing: const SizedBox(
        key: Key('voice-cursor'),
        width: 4,
        height: 14,
      ),
      style: const TextStyle(fontSize: 14),
    );
  }
}
