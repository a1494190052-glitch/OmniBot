import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/streaming_text.dart';
import 'package:ui/widgets/typewriter_text.dart';

void main() {
  test('detects partial markdown table candidates', () {
    expect(omnibotMarkdownContainsTableCandidate('名称 | 状态'), isTrue);
    expect(omnibotMarkdownContainsTableCandidate('|:---'), isTrue);
    expect(omnibotMarkdownContainsTableCandidate('只是普通段落'), isFalse);
    expect(
      omnibotMarkdownWithoutTrailingTableCandidate('表格如下：\n\n| 序号 | 姓名 |'),
      '表格如下：',
    );
    const renderedTable =
        '好的，以下是一个示例表格：\n\n'
        '| 序号 | 姓名 |\n'
        '| --- | --- |\n'
        '| 1 | 张三 |';
    expect(
      omnibotMarkdownWithoutTrailingTableCandidate(
        '$renderedTable\n\n'
        '好的，以下是一个示例表格：\n'
        '| 序号 | 姓名 |\n'
        '|:---',
      ),
      renderedTable,
    );
  });

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

  testWidgets('StreamingText keeps markdown tables out of the selection tree', (
    tester,
  ) async {
    const tableText =
        '表格如下：\n\n'
        '| 名称 | 状态 |\n'
        '| --- | --- |\n'
        '| A | 通过 |\n'
        '| B | 待处理 |';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(
            enableMarkdown: true,
            selectable: true,
            fullText: tableText,
            style: TextStyle(fontSize: 14),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(
      tester
          .widgetList<SelectionContainer>(find.byType(SelectionContainer))
          .any((widget) => widget.delegate == null),
      isTrue,
    );

    await tester.tap(find.text('A'));
    await tester.pump();
    expect(tester.takeException(), isNull);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(
            enableMarkdown: true,
            selectable: true,
            fullText: '切回普通 **Markdown** 段落',
            style: TextStyle(fontSize: 14),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.textContaining('Markdown'), findsOneWidget);
  });

  testWidgets(
    'StreamingText renders table fast-path tails outside selectable markdown',
    (tester) async {
      const prefix = '表格如下：\n\n';
      const fullText =
          '$prefix| 名称 | 状态 |\n'
          '| --- | --- |\n'
          '| A | 通过 |';

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              markdownRenderedLength: prefix.length,
              fullText: fullText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(SelectionArea), findsNothing);
      expect(
        find.byKey(const ValueKey('omnibot-streaming-table-tail')),
        findsNothing,
      );
      expect(find.textContaining('| A |'), findsNothing);

      await tester.tap(find.textContaining('表格如下'));
      await tester.pump();
      expect(tester.takeException(), isNull);

      const headerFlushed = '$prefix| 序号 | 姓名 | 部门 | 职位 | 入职日期 | 状态 |\n';
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              markdownRenderedLength: headerFlushed.length,
              fullText: '$headerFlushed|:---',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.textContaining('| 序号 |'), findsNothing);
      expect(find.textContaining('|:---'), findsNothing);

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              markdownRenderedLength: prefix.length,
              fullText: '$fullText\n\n后续说明',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.textContaining('| A |'), findsNothing);
      expect(find.text('后续说明'), findsOneWidget);

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              fullText: fullText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(Table), findsOneWidget);
      expect(find.byType(SelectionArea), findsNothing);
    },
  );

  testWidgets(
    'StreamingText hides dangling duplicated table snapshots in full markdown path',
    (tester) async {
      const fullText =
          '好的，以下是一个示例表格：\n\n'
          '| 序号 | 姓名 |\n'
          '| --- | --- |\n'
          '| 1 | 张三 |\n\n'
          '好的，以下是一个示例表格：\n'
          '| 序号 | 姓名 |\n'
          '|:---';

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              fullText: fullText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(Table), findsOneWidget);
      expect(find.byType(SelectionArea), findsNothing);
      expect(find.textContaining('| 序号 |'), findsNothing);
      expect(find.textContaining('|:---'), findsNothing);
    },
  );
}
