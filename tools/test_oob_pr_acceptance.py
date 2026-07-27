import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import oob_pr_acceptance
import oob_pr_freeze_check

from oob_pr_acceptance import (
    MockVlmProvider,
    archive_historical_run_logs,
    el,
    evaluate_historical_vlm_reselection,
    function_stop_spec,
    has_focused_edit_text,
    manual_registration_evidence,
    parse_openai_model_turn,
    semantic_binding_evidence,
    settings_search_target,
    storage_artifact_stem,
)


class AndroidIntentExtraTest(unittest.TestCase):
    def test_long_extra_never_serializes_as_float(self) -> None:
        self.assertEqual(el("waitMs", 180.0 * 1_000), ["--el", "waitMs", "180000"])


class FunctionStopSpecTest(unittest.TestCase):
    def test_uses_canonical_function_schema(self) -> None:
        function = function_stop_spec("stop_test", "test", 30_000, "state_1")

        self.assertEqual(function["schema_version"], "omniflow.function.v2")
        self.assertEqual(
            set(function),
            {
                "schema_version",
                "function_id",
                "name",
                "description",
                "input_schema",
                "bindings",
                "steps",
                "checker_rules",
                "agent_visible",
            },
        )
        self.assertEqual(function["steps"][0]["source_state_id"], "state_1")
        self.assertEqual(function["steps"][0]["action"], {"tool": "wait", "args": {"duration_ms": 30_000}})


class ManualRegistrationEvidenceTest(unittest.TestCase):
    def test_reads_only_canonical_run_log_and_function(self) -> None:
        evidence = manual_registration_evidence(
            {
                "success": True,
                "run_log": {
                    "schema_version": "omniflow.canonical_run_log.v1",
                    "run_id": "run_1",
                    "status": "succeeded",
                    "steps": [{"step_index": 0}],
                },
                "function": {
                    "schema_version": "omniflow.function.v2",
                    "function_id": "function_1",
                },
            }
        )

        self.assertEqual(
            evidence,
            {
                "recording_success": True,
                "function_registered": True,
                "function_id": "function_1",
                "run_id": "run_1",
                "action_count": 1,
            },
        )

    def test_rejects_legacy_top_level_registration_aliases(self) -> None:
        evidence = manual_registration_evidence(
            {
                "success": True,
                "function_id": "legacy_function",
                "run_id": "legacy_run",
                "conversion_success": True,
                "action_count": 1,
            }
        )

        self.assertFalse(evidence["recording_success"])
        self.assertFalse(evidence["function_registered"])
        self.assertEqual(evidence["function_id"], "")
        self.assertEqual(evidence["run_id"], "")
        self.assertEqual(evidence["action_count"], 0)


class SemanticBindingEvidenceTest(unittest.TestCase):
    def test_requires_canonical_query_binding(self) -> None:
        evidence = semantic_binding_evidence(
            {
                "input_schema": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                    "additionalProperties": False,
                },
                "bindings": [
                    {
                        "source": "$.arguments.query",
                        "target": "$.steps[0].action.args.text",
                    }
                ],
            }
        )

        self.assertTrue(evidence["query_property"])
        self.assertTrue(evidence["query_required"])
        self.assertTrue(evidence["binding_found"])


class SettingsSearchTargetTest(unittest.TestCase):
    def test_uses_generic_search_semantics(self) -> None:
        xml = """<hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1260,2800]">
        <node resource-id="vendor:id/settings_search" class="android.view.ViewGroup"
          text="搜索设置项" clickable="true" enabled="true" bounds="[84,182][1007,294]" />
        </node></hierarchy>"""

        self.assertEqual(settings_search_target(xml), (545, 238))

    def test_prefers_edit_text_without_vendor_resource_ids(self) -> None:
        xml = """<hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1260,2800]">
        <node resource-id="vendor:id/query" class="android.widget.EditText"
          enabled="true" focused="false" bounds="[213,205][1007,271]" />
        </node></hierarchy>"""

        self.assertEqual(settings_search_target(xml), (610, 238))
        self.assertFalse(has_focused_edit_text(xml))

    def test_detects_already_focused_edit_text(self) -> None:
        xml = """<hierarchy><node class="android.widget.EditText" enabled="true"
          focused="true" bounds="[10,20][110,80]" /></hierarchy>"""

        self.assertTrue(has_focused_edit_text(xml))


class MockResolverTest(unittest.TestCase):
    def test_selects_matching_zero_argument_function(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)
        prompt = "Select one reusable Function for the user's goal and extract its arguments.\n\n" + (
            '{"goal":"执行 pr_accept_unique",'
            '"functions":[{"function_id":"other","description":"执行 Android screen target workflow",'
            '"input_schema":{"type":"object","properties":{},"required":[]}},'
            '{"function_id":"target","description":"workflow pr_accept_unique",'
            '"input_schema":{"type":"object","properties":{},"required":[]}}]}'
        )

        result = provider._resolver_response([{"role": "user", "content": prompt}])

        self.assertEqual(result, {"function_id": "target", "arguments": {}})

    def test_does_not_invent_required_arguments(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)
        prompt = "Select one reusable Function for the user's goal and extract its arguments.\n\n" + (
            '{"goal":"搜索 pr_accept_unique",'
            '"functions":[{"function_id":"target","description":"search pr_accept_unique",'
            '"input_schema":{"type":"object","properties":{"query":{"type":"string"}},'
            '"required":["query"]}}]}'
        )

        result = provider._resolver_response([{"role": "user", "content": prompt}])

        self.assertEqual(result, {"function_id": None, "arguments": {}})

    def test_argument_probe_can_bypass_function_recall(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)
        provider.reset_gui_argument_probe("canonical argument reselection probe")
        prompt = "Select one reusable Function for the user's goal and extract its arguments.\n\n" + (
            '{"goal":"Tap once for the canonical argument reselection probe",'
            '"functions":[{"function_id":"target",'
            '"description":"canonical argument reselection probe",'
            '"input_schema":{"type":"object","properties":{},"required":[]}}]}'
        )

        result = provider._resolver_response([{"role": "user", "content": prompt}])

        self.assertEqual(result, {"function_id": None, "arguments": {}})

    def test_reads_only_the_latest_user_text_for_stop_and_gui_goal(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)
        messages = [
            {"role": "system", "content": "Tools may be stopped."},
            {"role": "user", "content": "执行 pr_accept_unique"},
        ]

        user_text = provider._last_user_text(messages)

        self.assertEqual(user_text, "执行 pr_accept_unique")
        self.assertFalse(provider._is_stop_request(user_text))

    def test_enhancement_preserves_semantics_and_binds_input_text(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)
        prompt = "Improve the reusable Android automation Function below for future recall.\nFunction:\n" + (
            '{"name":"搜索 pr_accept_unique","description":"输入并搜索 pr_accept_unique",'
            '"parameter_candidates":[{"step_index":1,"tool":"input_text",'
            '"arg_name":"text","recorded_value":"蜜雪冰城"}]}'
        )

        result = provider._enhancement_response([{"role": "user", "content": prompt}])

        self.assertIn("pr_accept_unique", result["name"])
        self.assertIn("pr_accept_unique", result["description"])
        self.assertEqual(
            result["parameters"],
            [{"name": "query", "description": "Text to enter", "step_index": 1, "arg_name": "text"}],
        )

    def test_finishes_after_recalled_function_action(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)
        selected, arguments, _, _ = provider._select_tool(
            [
                {"type": "function", "function": {"name": "click", "parameters": {}}},
                {
                    "type": "function",
                    "function": {
                        "name": "finished",
                        "parameters": {
                            "type": "object",
                            "properties": {"content": {"type": "string"}},
                            "required": ["content"],
                        },
                    },
                },
            ],
            has_tool_result=False,
            reselection_requested=False,
            replay_completed=True,
        )

        self.assertEqual(selected, "finished")
        self.assertEqual(arguments, {"content": "mock acceptance"})

    def test_reselects_after_canonical_no_progress_signal(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)

        selected, arguments, _, _ = provider._select_tool(
            [
                {"type": "function", "function": {"name": "click", "parameters": {}}},
                {
                    "type": "function",
                    "function": {
                        "name": "finished",
                        "parameters": {
                            "type": "object",
                            "properties": {"content": {"type": "string"}},
                            "required": ["content"],
                        },
                    },
                },
            ],
            has_tool_result=False,
            reselection_requested=True,
            replay_completed=False,
        )

        self.assertEqual(selected, "finished")
        self.assertEqual(arguments, {"content": "mock acceptance"})

    def test_recognizes_only_canonical_no_progress_signals(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)

        self.assertTrue(provider._has_reselection_signal("action_completed_without_state_change"))
        self.assertTrue(provider._has_reselection_signal("repeated_action_without_progress"))
        self.assertFalse(provider._has_reselection_signal("duplicate_operation_on_unchanged_page"))

    def test_injects_one_historical_bad_gui_call_then_returns_to_schema_values(self) -> None:
        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)

        first_args, first_injected = provider._inject_invalid_gui_arguments(
            selected_tool="click",
            selected_args={"x": 1, "y": 1},
            tool_names=["click", "finished"],
        )
        completed_before_valid_call = provider._gui_probe_completed(
            [{"type": "function", "function": {"name": "finished"}}]
        )
        second_args, second_injected = provider._inject_invalid_gui_arguments(
            selected_tool="click",
            selected_args={"x": 1, "y": 1},
            tool_names=["click"],
        )
        completed_after_valid_call = provider._gui_probe_completed(
            [{"type": "function", "function": {"name": "finished"}}]
        )

        self.assertTrue(first_injected)
        self.assertEqual(first_args, {"x": [1, 2], "y": 1})
        self.assertFalse(completed_before_valid_call)
        self.assertFalse(second_injected)
        self.assertEqual(second_args, {"x": 1, "y": 1})
        self.assertTrue(completed_after_valid_call)

        provider.reset_gui_argument_probe()

        self.assertFalse(
            provider._gui_probe_completed(
                [{"type": "function", "function": {"name": "finished"}}]
            )
        )


class HistoricalRunLogArchiveTest(unittest.TestCase):
    def test_storage_stem_matches_android_runlog_store(self) -> None:
        self.assertEqual(
            storage_artifact_stem("vlm/run:1"),
            "9929b27e8ca37bed_vlm_run_1",
        )

    def test_archives_runlog_events_and_referenced_states(self) -> None:
        run_id = "vlm/run:1"
        state_ids = ["state-before", "state-after", "state-final"]
        run_stem = storage_artifact_stem(run_id)
        run_log = {
            "schema_version": "omniflow.canonical_run_log.v1",
            "run_id": run_id,
            "goal": "historical reselection",
            "status": "failed",
            "success": False,
            "steps": [
                {
                    "step_index": 0,
                    "before_state_id": state_ids[0],
                    "action": {"tool": "click", "args": {"x": 10, "y": 20}},
                    "result": {"success": True},
                    "after_state_id": state_ids[1],
                }
            ],
            "final_state_id": state_ids[2],
            "diagnostics": {
                "planner": {
                    "rejected_tool_calls": [
                        {
                            "turn_index": 0,
                            "tool": "click",
                            "error": "canonical_action_arg_type_invalid:x",
                        }
                    ]
                }
            },
        }
        files = {
            f"files/run_logs/{run_stem}.json": json.dumps(run_log).encode(),
            f"files/run_logs/{run_stem}.events.ndjson": b'{"event_type":"finish"}\n',
        }
        for state_id in state_ids:
            state_stem = storage_artifact_stem(state_id)
            files[f"files/run_logs/states/{state_stem}.json"] = json.dumps(
                {"state_id": state_id, "display": {"width": 100, "height": 200}}
            ).encode()
            files[f"files/run_logs/states/{state_stem}.xml"] = (
                f'<hierarchy state_id="{state_id}" />'
            ).encode()
        files[f"files/run_logs/states/{storage_artifact_stem(state_ids[0])}.jpg"] = (
            b"\xff\xd8\xffmock-jpeg"
        )

        class FakeAdb:
            def read_app_file(self, file_name):
                return files.get(file_name)

        with tempfile.TemporaryDirectory() as temporary_dir:
            result = archive_historical_run_logs(
                FakeAdb(),
                [run_id],
                Path(temporary_dir),
            )
            archive_root = Path(result["archive_root"])

            self.assertTrue(result["success"])
            self.assertEqual(result["run_count"], 1)
            self.assertEqual(result["state_count"], 3)
            self.assertEqual(result["runs"][0]["rejected_tool_call_count"], 1)
            self.assertEqual(
                result["runs"][0]["rejected_tool_call_errors"],
                ["canonical_action_arg_type_invalid:x"],
            )
            self.assertTrue((archive_root / "manifest.json").is_file())
            self.assertTrue((archive_root / run_stem / "run_log.json").is_file())
            self.assertTrue((archive_root / run_stem / "events.ndjson").is_file())
            self.assertTrue(
                (archive_root / run_stem / "states" / storage_artifact_stem(state_ids[0]) / "state.jpg").is_file()
            )
            self.assertEqual(
                result["runs"][0]["states"][0]["screenshot_mime_type"],
                "image/jpeg",
            )

    def test_does_not_archive_invalid_screenshot_payload(self) -> None:
        run_id = "run-invalid-screenshot"
        state_id = "state-invalid-screenshot"
        run_stem = storage_artifact_stem(run_id)
        state_stem = storage_artifact_stem(state_id)
        files = {
            f"files/run_logs/{run_stem}.json": json.dumps(
                {
                    "schema_version": "omniflow.canonical_run_log.v1",
                    "run_id": run_id,
                    "steps": [],
                    "final_state_id": state_id,
                }
            ).encode(),
            f"files/run_logs/{run_stem}.events.ndjson": b'{"event_type":"finish"}\n',
            f"files/run_logs/states/{state_stem}.json": json.dumps(
                {"state_id": state_id, "display": {"width": 100, "height": 200}}
            ).encode(),
            f"files/run_logs/states/{state_stem}.xml": b"<hierarchy />",
            f"files/run_logs/states/{state_stem}.jpg": b"screenshot capture failed",
        }

        class FakeAdb:
            def read_app_file(self, file_name):
                return files.get(file_name)

        with tempfile.TemporaryDirectory() as temporary_dir:
            result = archive_historical_run_logs(
                FakeAdb(),
                [run_id],
                Path(temporary_dir),
            )

        self.assertTrue(result["success"])
        self.assertFalse(result["runs"][0]["states"][0]["screenshot_archived"])
        self.assertEqual(
            result["runs"][0]["states"][0]["screenshot_error"],
            "invalid_image_payload",
        )

    def test_reports_missing_state_artifacts(self) -> None:
        run_id = "run-missing-state"
        run_stem = storage_artifact_stem(run_id)
        run_log = {
            "schema_version": "omniflow.canonical_run_log.v1",
            "run_id": run_id,
            "steps": [
                {
                    "step_index": 0,
                    "before_state_id": "state-missing",
                    "action": {"tool": "back", "args": {}},
                    "result": {"success": False, "error": "blocked"},
                    "after_state_id": "state-missing",
                }
            ],
        }
        files = {
            f"files/run_logs/{run_stem}.json": json.dumps(run_log).encode(),
            f"files/run_logs/{run_stem}.events.ndjson": b"{}\n",
        }

        class FakeAdb:
            def read_app_file(self, file_name):
                return files.get(file_name)

        with tempfile.TemporaryDirectory() as temporary_dir:
            result = archive_historical_run_logs(
                FakeAdb(),
                [run_id],
                Path(temporary_dir),
            )

        self.assertFalse(result["success"])
        self.assertTrue(any(item.endswith(":state.json") for item in result["missing_artifacts"]))
        self.assertTrue(any(item.endswith(":state.xml") for item in result["missing_artifacts"]))


class HistoricalVlmReselectionTest(unittest.TestCase):
    def test_skips_function_replay_steps_as_non_vlm_choices(self) -> None:
        run_id = "historical-function-run"
        run_stem = storage_artifact_stem(run_id)
        requests = []

        with tempfile.TemporaryDirectory() as temporary_dir:
            archive_root = Path(temporary_dir) / "historical_runlogs"
            run_dir = archive_root / run_stem
            run_dir.mkdir(parents=True)
            (run_dir / "run_log.json").write_text(
                json.dumps(
                    {
                        "schema_version": "omniflow.canonical_run_log.v1",
                        "run_id": run_id,
                        "goal": "Run a saved Function",
                        "steps": [
                            {
                                "step_index": 0,
                                "before_state_id": "state-before",
                                "action": {"tool": "click", "args": {"x": 1, "y": 1}},
                                "result": {"success": True},
                                "after_state_id": "state-after",
                                "metadata": {"function_id": "saved-function"},
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            result = evaluate_historical_vlm_reselection(
                {
                    "schema_version": "oob.acceptance.runlog_archive.v1",
                    "archive_root": str(archive_root),
                    "runs": [
                        {
                            "run_id": run_id,
                            "directory": run_stem,
                            "complete": True,
                        }
                    ],
                },
                model="gui-model",
                turn_client=lambda request: requests.append(request),
                run_ids={run_id},
            )

        self.assertFalse(result["success"])
        self.assertEqual(result["case_count"], 0)
        self.assertEqual(requests, [])
        self.assertEqual(
            result["skipped"][0]["reason"],
            "function_replay_step_not_vlm_selection",
        )

    def test_reselects_from_archived_before_state_with_canonical_retry(self) -> None:
        run_id = "historical-run-1"
        state_id = "historical-state-1"
        run_stem = storage_artifact_stem(run_id)
        state_stem = storage_artifact_stem(state_id)
        requests = []
        responses = [
            self._model_turn(
                "click",
                {"summary": "Tap", "x": [1.08, 2.4], "y": 1.08},
            ),
            self._model_turn("click", {"summary": "Tap", "x": 1.08, "y": 2.4}),
        ]

        with tempfile.TemporaryDirectory() as temporary_dir:
            archive_root = Path(temporary_dir) / "historical_runlogs"
            run_dir = archive_root / run_stem
            state_dir = run_dir / "states" / state_stem
            state_dir.mkdir(parents=True)
            (run_dir / "run_log.json").write_text(
                json.dumps(
                    {
                        "schema_version": "omniflow.canonical_run_log.v1",
                        "run_id": run_id,
                        "goal": "Tap once",
                        "steps": [
                            {
                                "step_index": 0,
                                "before_state_id": state_id,
                                "action": {"tool": "click", "args": {"x": 1, "y": 1}},
                                "result": {"success": True},
                                "after_state_id": "historical-state-2",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (state_dir / "state.json").write_text(
                json.dumps(
                    {
                        "state_id": state_id,
                        "package_name": "com.android.settings",
                        "activity_name": "Settings",
                        "display": {"width": 1080, "height": 2400},
                    }
                ),
                encoding="utf-8",
            )
            (state_dir / "state.xml").write_text("<hierarchy />", encoding="utf-8")
            archive = {
                "schema_version": "oob.acceptance.runlog_archive.v1",
                "archive_root": str(archive_root),
                "runs": [
                    {
                        "run_id": run_id,
                        "directory": run_stem,
                        "complete": True,
                    }
                ],
            }

            def turn_client(request):
                requests.append(request)
                return responses.pop(0)

            result = evaluate_historical_vlm_reselection(
                archive,
                model="gui-model",
                turn_client=turn_client,
                run_ids={run_id},
            )

        self.assertTrue(result["success"])
        self.assertEqual(result["case_count"], 1)
        self.assertEqual(result["schema_valid_count"], 1)
        self.assertEqual(result["tool_match_count"], 1)
        self.assertEqual(result["exact_action_match_count"], 1)
        self.assertEqual(result["rejected_tool_call_count"], 1)
        self.assertEqual(
            result["cases"][0]["rejected_tool_calls"][0]["error"],
            "canonical_action_arg_type_invalid:x",
        )
        self.assertGreater(len(requests[0]["tools"]), 1)
        self.assertEqual(
            [tool["function"]["name"] for tool in requests[1]["tools"]],
            ["click"],
        )

    def test_parses_streamed_native_tool_call_for_historical_selection(self) -> None:
        chunks = [
            {
                "model": "resolved-model",
                "choices": [
                    {
                        "delta": {
                            "tool_calls": [
                                {
                                    "index": 0,
                                    "id": "call-1",
                                    "type": "function",
                                    "function": {
                                        "name": "click",
                                        "arguments": '{"summary":"Tap","x":',
                                    },
                                }
                            ]
                        },
                        "finish_reason": None,
                    }
                ],
            },
            {
                "model": "resolved-model",
                "choices": [
                    {
                        "delta": {
                            "tool_calls": [
                                {
                                    "index": 0,
                                    "function": {"arguments": "1,\"y\":1}"},
                                }
                            ]
                        },
                        "finish_reason": "tool_calls",
                    }
                ],
                "usage": {"total_tokens": 2},
            },
        ]
        payload = "".join(
            f"data: {json.dumps(chunk)}\n\n"
            for chunk in chunks
        ) + "data: [DONE]\n\n"

        result = parse_openai_model_turn(
            payload,
            content_type="text/event-stream; charset=utf-8",
            requested_model="gui-model",
        )

        self.assertEqual(result["resolved_model"], "resolved-model")
        self.assertEqual(result["tool_calls"][0]["function"]["name"], "click")
        self.assertEqual(
            result["tool_calls"][0]["function"]["arguments"],
            '{"summary":"Tap","x":1,"y":1}',
        )
        self.assertEqual(result["usage"], {"total_tokens": 2})

    @staticmethod
    def _model_turn(tool: str, arguments: dict) -> dict:
        return {
            "requested_model": "gui-model",
            "resolved_model": "gui-model",
            "tool_calls": [
                {
                    "id": "call-1",
                    "type": "function",
                    "function": {
                        "name": tool,
                        "arguments": json.dumps(arguments),
                    },
                }
            ],
        }


class HistoricalRunLogFreezeGateTest(unittest.TestCase):
    def test_freeze_gate_requires_historical_runlog_archive(self) -> None:
        checks = [
            {"name": name, "success": True}
            for name in oob_pr_freeze_check.REQUIRED_CHECKS
            if name != "historical_runlog_archive"
        ]
        with tempfile.TemporaryDirectory() as temporary_dir:
            summary_path = Path(temporary_dir) / "summary.json"
            summary_path.write_text(
                json.dumps({"success": True, "checks": checks}),
                encoding="utf-8",
            )

            success, report = oob_pr_freeze_check.check_summary(summary_path)

        self.assertFalse(success)
        self.assertEqual(report["missing_checks"], ["historical_runlog_archive"])

    def test_freeze_gate_requires_historical_vlm_reselection(self) -> None:
        checks = [
            {"name": name, "success": True}
            for name in oob_pr_freeze_check.REQUIRED_CHECKS
            if name != "historical_vlm_reselection"
        ]
        with tempfile.TemporaryDirectory() as temporary_dir:
            summary_path = Path(temporary_dir) / "summary.json"
            summary_path.write_text(
                json.dumps({"success": True, "checks": checks}),
                encoding="utf-8",
            )

            success, report = oob_pr_freeze_check.check_summary(summary_path)

        self.assertFalse(success)
        self.assertEqual(report["missing_checks"], ["historical_vlm_reselection"])


class MockProviderCleanupTest(unittest.TestCase):
    def test_mock_provider_uses_an_isolated_profile_by_default(self) -> None:
        args = SimpleNamespace(
            mock_provider=True,
            mock_provider_port=0,
            mock_provider_model="mock",
            stop_delay_seconds=0,
            provider_profile_id="",
            provider_name="",
            provider_base_url="",
            provider_api_key="",
            provider_model="",
        )
        provider = SimpleNamespace(
            device_base_url="http://127.0.0.1:12345",
            model="mock",
            start=lambda adb: None,
        )

        with patch.object(oob_pr_acceptance, "MockVlmProvider", return_value=provider):
            result = oob_pr_acceptance.start_mock_provider_if_requested("adb", args)

        self.assertIs(result, provider)
        self.assertTrue(args.provider_profile_id.startswith("oob-pr-acceptance-"))

    def test_removes_reverse_even_when_probe_did_not_mark_it_reachable(self) -> None:
        class FakeAdb:
            def __init__(self) -> None:
                self.calls = []

            def run(self, args, **kwargs):
                self.calls.append((args, kwargs))

        provider = MockVlmProvider(model="mock", stop_delay_seconds=0)
        provider.port = 54321
        provider.reverse_enabled = False
        adb = FakeAdb()

        provider.stop(adb)

        self.assertEqual(adb.calls[0][0], ["reverse", "--remove", "tcp:54321"])


class ProviderIsolationTest(unittest.TestCase):
    def test_real_provider_gets_a_profile_id_before_snapshot(self) -> None:
        args = SimpleNamespace(
            device="device",
            package="package",
            mock_provider=False,
            provider_base_url="https://provider.example",
            provider_api_key="secret",
            provider_model="model",
            provider_profile_id="",
        )
        observed_profile_ids = []

        def state_operation(adb, provided_args, operation):
            observed_profile_ids.append(provided_args.provider_profile_id)
            return {"success": True, "restored": operation == "restore"}

        with (
            patch.object(oob_pr_acceptance, "Adb", return_value="adb"),
            patch.object(oob_pr_acceptance, "start_mock_provider_if_requested", return_value=None),
            patch.object(oob_pr_acceptance, "provider_state_operation", side_effect=state_operation),
            patch.object(oob_pr_acceptance, "_run_acceptance", return_value={"success": True}),
        ):
            result = oob_pr_acceptance.run_acceptance(args)

        self.assertEqual(result, {"success": True})
        self.assertEqual(len(observed_profile_ids), 2)
        self.assertTrue(args.provider_profile_id.startswith("oob-pr-acceptance-"))
        self.assertEqual(observed_profile_ids, [args.provider_profile_id] * 2)

    def test_acceptance_restores_provider_and_proxy_after_success(self) -> None:
        args = SimpleNamespace(
            device="device",
            package="package",
            mock_provider=True,
            provider_base_url="",
            provider_api_key="",
            provider_model="",
        )
        provider = SimpleNamespace(stop=lambda adb: stopped.append(adb))
        stopped = []
        operations = []
        restored_proxies = []

        def start_provider(adb, provided_args):
            provided_args.provider_base_url = "http://127.0.0.1:12345"
            provided_args.provider_api_key = "mock"
            provided_args.provider_model = "mock"
            return provider

        def state_operation(adb, provided_args, operation):
            operations.append(operation)
            return {"success": True, "restored": operation == "restore"}

        with (
            patch.object(oob_pr_acceptance, "Adb", return_value="adb"),
            patch.object(oob_pr_acceptance, "capture_device_proxy", return_value="proxy"),
            patch.object(oob_pr_acceptance, "clear_device_proxy_for_mock_provider"),
            patch.object(oob_pr_acceptance, "start_mock_provider_if_requested", side_effect=start_provider),
            patch.object(oob_pr_acceptance, "provider_state_operation", side_effect=state_operation),
            patch.object(oob_pr_acceptance, "_run_acceptance", return_value={"success": True}),
            patch.object(
                oob_pr_acceptance,
                "restore_device_proxy",
                side_effect=lambda adb, proxy: restored_proxies.append((adb, proxy)),
            ),
        ):
            result = oob_pr_acceptance.run_acceptance(args)

        self.assertEqual(result, {"success": True})
        self.assertEqual(operations, ["snapshot", "restore"])
        self.assertEqual(stopped, ["adb"])
        self.assertEqual(restored_proxies, [("adb", "proxy")])

    def test_snapshot_failure_still_stops_provider_and_restores_proxy(self) -> None:
        args = SimpleNamespace(
            device="device",
            package="package",
            mock_provider=True,
            provider_base_url="http://127.0.0.1:12345",
            provider_api_key="mock",
            provider_model="mock",
        )
        stopped = []
        restored_proxies = []
        provider = SimpleNamespace(stop=lambda adb: stopped.append(adb))

        with (
            patch.object(oob_pr_acceptance, "Adb", return_value="adb"),
            patch.object(oob_pr_acceptance, "capture_device_proxy", return_value="proxy"),
            patch.object(oob_pr_acceptance, "clear_device_proxy_for_mock_provider"),
            patch.object(oob_pr_acceptance, "start_mock_provider_if_requested", return_value=provider),
            patch.object(
                oob_pr_acceptance,
                "provider_state_operation",
                side_effect=RuntimeError("snapshot failed"),
            ),
            patch.object(
                oob_pr_acceptance,
                "restore_device_proxy",
                side_effect=lambda adb, proxy: restored_proxies.append((adb, proxy)),
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "snapshot failed"):
                oob_pr_acceptance.run_acceptance(args)

        self.assertEqual(stopped, ["adb"])
        self.assertEqual(restored_proxies, [("adb", "proxy")])


if __name__ == "__main__":
    unittest.main()
