import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';

void main() {
  test('resolveAgentToolRunId prefers the VLM child run id', () {
    expect(
      resolveAgentToolRunId({
        'toolName': 'vlm_task',
        'taskId': 'parent-agent-run',
        'run_id': 'vlm-run-123',
      }),
      'vlm-run-123',
    );
  });

  test('resolveAgentToolRunId reads canonical run_id from the VLM result', () {
    expect(
      resolveAgentToolRunId({
        'toolName': 'vlm-task',
        'taskId': 'parent-agent-run',
        'resultPreviewJson': jsonEncode({'run_id': 'vlm-run-456'}),
      }),
      'vlm-run-456',
    );
    expect(
      resolveAgentToolRunId({
        'toolName': 'terminal_execute',
        'run_id': 'not-a-vlm-run',
      }),
      isNull,
    );
  });

  test(
    'buildAgentToolTranscript renders non-terminal tool as pseudo command',
    () {
      final transcript = buildAgentToolTranscript({
        'toolName': 'file_read',
        'displayName': '读取文件',
        'toolType': 'workspace',
        'argsJson': jsonEncode({
          'path': '/workspace/README.md',
          'maxChars': 4000,
          'tool_title': '查看 README',
        }),
        'resultPreviewJson': jsonEncode({
          'path': '/workspace/README.md',
          'size': 32,
          'content': 'hello world',
        }),
        'status': 'success',
        'summary': '已读取文件',
      });

      expect(
        transcript.promptLine,
        r'$ file_read --path /workspace/README.md --maxChars 4000',
      );
      expect(transcript.outputText, contains('path: /workspace/README.md'));
      expect(transcript.outputText, contains('size: 32'));
      expect(transcript.outputText, contains('content: hello world'));
    },
  );

  test(
    'buildAgentToolTranscript renders terminal tool using native command',
    () {
      final transcript = buildAgentToolTranscript({
        'toolName': 'terminal_execute',
        'displayName': '终端执行',
        'toolType': 'terminal',
        'argsJson': jsonEncode({
          'command': 'git status',
          'workingDirectory': '/workspace',
        }),
        'terminalOutput': 'On branch main',
        'status': 'success',
        'summary': '终端命令执行成功',
      });

      expect(transcript.promptLine, r"$ cd /workspace && git status");
      expect(transcript.outputText, 'On branch main');
      expect(transcript.previewText, 'On branch main');
    },
  );

  test(
    'buildAgentToolTranscript hides legacy Codex namespace for Claude tools',
    () {
      final transcript = buildAgentToolTranscript({
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'toolName': 'codex.tool',
        'toolTitle': 'Read settings.json',
        'displayName': 'Read settings.json',
        'toolType': 'workspace',
        'argsJson': jsonEncode({
          'id': 'tool-call-42',
          'path': '/root/.claude/settings.json',
        }),
        'resultPreviewJson': jsonEncode({'status': 'ok'}),
        'status': 'success',
      });

      expect(transcript.promptLine, 'Claude Code · Read settings.json');
      expect(transcript.promptLine, isNot(contains('codex.tool')));
      expect(transcript.promptLine, isNot(contains('--id')));
    },
  );

  test(
    'buildAgentToolTranscript hides generic running placeholder for terminal output area',
    () {
      final transcript = buildAgentToolTranscript({
        'toolName': 'terminal_execute',
        'displayName': '终端执行',
        'toolType': 'terminal',
        'argsJson': jsonEncode({
          'command': 'npm install',
          'workingDirectory': '/workspace',
        }),
        'status': 'running',
        'summary': '正在调用内嵌 Alpine 终端执行命令',
        'progress': '终端输出更新中',
      });

      expect(transcript.promptLine, r'$ cd /workspace && npm install');
      expect(transcript.outputText, isEmpty);
      expect(transcript.previewText, isEmpty);
    },
  );

  test('buildAgentToolTranscript renders VLM step context', () {
    final transcript = buildAgentToolTranscript({
      'toolName': 'vlm_task',
      'toolType': 'builtin',
      'status': 'running',
      'progress': '点击搜索框',
      'vlmStepThinking': '搜索框已经可见',
      'vlmStepAction': {
        'tool': 'click',
        'args': {'x': 420, 'y': 610},
      },
    });

    expect(transcript.outputText, contains('思考：搜索框已经可见'));
    expect(transcript.outputText, contains('动作：{"tool":"click"'));
    expect(transcript.outputText, contains('"x":420'));
  });
}
