from __future__ import annotations

from omniflow.schemas import vlm_action_tools
from omniflow.vlm_coordinates import (
    screen_context_to_pixels,
    screen_pixel_args_to_canonical,
    screen_pixel_tools,
)
import pytest

DISPLAY = {"width": 1260, "height": 2800}


def test_vlm_tool_schema_uses_current_original_screen_pixels() -> None:
    tools = screen_pixel_tools(vlm_action_tools(include_summary=True), DISPLAY)
    click = next(tool for tool in tools if tool["function"]["name"] == "click")
    properties = click["function"]["parameters"]["properties"]

    assert properties["x"]["maximum"] == 1260
    assert properties["y"]["maximum"] == 2800
    assert "Raw X pixel coordinate" in properties["x"]["description"]


def test_vlm_raw_pixels_are_always_converted_to_canonical_coordinates() -> None:
    args, metadata = screen_pixel_args_to_canonical(
        tool="click",
        args={"x": 500, "y": 500},
        display=DISPLAY,
    )

    assert args["x"] == pytest.approx(396.825397)
    assert args["y"] == pytest.approx(178.571429)
    assert metadata is not None
    assert metadata["name"] == "screen_pixels_to_relative_0_1000.v1"


def test_vlm_real_qwen_pixels_convert_without_magnitude_guessing() -> None:
    args, _metadata = screen_pixel_args_to_canonical(
        tool="click",
        args={"x": 126, "y": 2649},
        display=DISPLAY,
    )

    assert args["x"] == 100
    assert args["y"] == pytest.approx(946.071429)


def test_canonical_history_is_converted_to_pixels_before_vlm_call() -> None:
    context = screen_context_to_pixels(
        {
            "previous_action": {"tool": "click", "args": {"x": 500, "y": 500}},
            "recent_actions": [
                {
                    "tool": "swipe",
                    "args": {
                        "direction": "up",
                        "x1": 500,
                        "y1": 800,
                        "x2": 500,
                        "y2": 200,
                    },
                    "success": True,
                }
            ],
        },
        DISPLAY,
    )

    assert context["previous_action"]["args"] == {"x": 630, "y": 1400}
    assert context["recent_actions"][0]["args"] == {
        "direction": "up",
        "x1": 630,
        "y1": 2240,
        "x2": 630,
        "y2": 560,
    }


def test_vlm_pixel_output_must_fit_current_display() -> None:
    with pytest.raises(ValueError, match="canonical_action_arg_range_invalid:y"):
        screen_pixel_args_to_canonical(
            tool="click",
            args={"x": 126, "y": 2801},
            display=DISPLAY,
        )


def test_coordinate_vlm_call_requires_display() -> None:
    with pytest.raises(ValueError, match="vlm_coordinate_display_required"):
        screen_pixel_args_to_canonical(
            tool="click",
            args={"x": 126, "y": 2649},
            display=None,
        )
