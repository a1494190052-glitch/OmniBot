from __future__ import annotations

from collections import Counter
import math
import re
from typing import Any
import xml.etree.ElementTree as ET

import numpy as np

from omniflow.embedding import PageEncoder
from omniflow.model import Function, Observation, Page, PageMatch, RecallResult


def page_representation(
    observation: Observation,
    encoder: PageEncoder | None = None,
) -> dict[str, Any]:
    model = encoder or PageEncoder()
    tokens = _token_features(observation)
    embedding = model.embed(observation)
    return {
        "package": str(observation.package_name or ""),
        "token_features": tokens,
        "page_vector": embedding.vector.tolist(),
        "encoder_name": model.name,
        "encoder_version": embedding.encoder_version,
        "dimension": model.dimension,
        "weights_hash": embedding.weights_hash,
    }


def _token_features(observation: Observation) -> dict[str, int]:
    xml_text = str(observation.xml or "").strip()
    tokens: Counter[str] = Counter()
    if xml_text:
        try:
            root = ET.fromstring(xml_text)
            for depth, element in _walk(root):
                attributes = element.attrib
                class_name = str(attributes.get("class") or element.tag).rsplit(".", 1)[
                    -1
                ]
                tokens[f"class:{class_name}"] += 1
                tokens[f"depth:{min(depth, 8)}"] += 1
                for name in ("clickable", "editable", "scrollable", "checkable"):
                    if str(attributes.get(name) or "").lower() == "true":
                        tokens[f"{name}:1"] += 1
                resource_id = str(attributes.get("resource-id") or "").rsplit("/", 1)[
                    -1
                ]
                if resource_id:
                    tokens[f"id:{resource_id}"] += 1
                text = _normalize_text(
                    attributes.get("text") or attributes.get("content-desc")
                )
                if text and len(text) <= 48:
                    tokens[f"text:{text}"] += 1
        except ET.ParseError:
            tokens.update(re.findall(r"[A-Za-z_][A-Za-z0-9_.:-]+", xml_text))
    return dict(sorted(tokens.items()))


def match_page(
    observation: Observation,
    pages: list[Page],
    *,
    threshold: float = 0.72,
    representation: str = "page_vector",
    encoder: PageEncoder | None = None,
) -> PageMatch | None:
    current_package = str(observation.package_name or "")
    model = encoder or PageEncoder()
    if representation == "tokens":
        query: dict[str, int] | np.ndarray = _token_features(observation)
    elif representation == "page_vector":
        query = model.embed(observation).vector
        if not np.any(query):
            return None
    else:
        raise ValueError(f"unsupported_representation:{representation}")
    best: PageMatch | None = None
    for page in pages:
        if current_package and page.package and current_package != page.package:
            continue
        scores = [
            _representation_score(
                query,
                item,
                representation=representation,
                encoder=model,
                package=page.package,
            )
            for item in page.representations
        ]
        score = max(scores, default=0.0)
        if score >= threshold and (best is None or score > best.score):
            best = PageMatch(page.id, score)
    return best


def _representation_score(
    query: dict[str, int] | np.ndarray,
    stored: dict[str, Any],
    *,
    representation: str,
    encoder: PageEncoder,
    package: str,
) -> float:
    if representation == "tokens":
        features = stored.get("token_features") or stored.get("features") or {}
        return _sparse_cosine(query, features) if isinstance(query, dict) else 0.0
    vector = stored.get("page_vector")
    compatible = (
        stored.get("encoder_name") == encoder.name
        and stored.get("encoder_version") == encoder.version
        and stored.get("weights_hash") == encoder.weights.hash
        and isinstance(vector, list)
        and len(vector) == encoder.dimension
    )
    if compatible:
        candidate = np.asarray(vector, dtype=np.float32)
    else:
        return 0.0
    return _dense_cosine(query, candidate) if isinstance(query, np.ndarray) else 0.0


def recall_functions(
    goal: str,
    observation: Observation,
    *,
    pages: list[Page],
    functions: dict[str, Function],
    matcher=match_page,
) -> RecallResult:
    page_match = matcher(observation, pages)
    allowed_ids: set[str] = {
        function.id for function in functions.values() if function.from_page is None
    }
    if page_match is not None:
        page = next((item for item in pages if item.id == page_match.page_id), None)
        if page is not None:
            allowed_ids.update(page.function_ids)
    scored = [
        (_goal_score(goal, functions[item].description), functions[item])
        for item in allowed_ids
        if item in functions
    ]
    ranked = [
        function
        for score, function in sorted(
            scored,
            key=lambda item: (-item[0], item[1].id),
        )
        if score > 0
    ]
    return RecallResult(
        functions=ranked,
        page_id=page_match.page_id if page_match else None,
        page_score=page_match.score if page_match else 0.0,
    )


def _walk(root: ET.Element):
    stack = [(0, root)]
    while stack:
        depth, element = stack.pop()
        yield depth, element
        stack.extend((depth + 1, child) for child in reversed(list(element)))


def _normalize_text(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip().lower())


def _sparse_cosine(left: dict[str, int], right: dict[str, int]) -> float:
    if not left or not right:
        return 0.0
    shared = set(left) & set(right)
    dot = sum(float(left[key]) * float(right[key]) for key in shared)
    left_norm = math.sqrt(sum(float(value) ** 2 for value in left.values()))
    right_norm = math.sqrt(sum(float(value) ** 2 for value in right.values()))
    if not left_norm or not right_norm:
        return 0.0
    return dot / (left_norm * right_norm)


def _dense_cosine(left: np.ndarray, right: np.ndarray) -> float:
    left_array = np.asarray(left, dtype=np.float32).reshape(-1)
    right_array = np.asarray(right, dtype=np.float32).reshape(-1)
    if left_array.shape != right_array.shape or not left_array.size:
        return 0.0
    denominator = float(np.linalg.norm(left_array) * np.linalg.norm(right_array))
    if denominator <= 1e-9:
        return 0.0
    return float(np.dot(left_array, right_array) / denominator)


def _goal_score(goal: str, description: str) -> float:
    goal_tokens = set(re.findall(r"[\w\u4e00-\u9fff]+", goal.lower()))
    description_tokens = set(re.findall(r"[\w\u4e00-\u9fff]+", description.lower()))
    if not goal_tokens or not description_tokens:
        return 0.0
    return len(goal_tokens & description_tokens) / len(goal_tokens | description_tokens)
