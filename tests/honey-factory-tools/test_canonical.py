from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools.canonical import format_number, loads, serialize, sha256


def test_matches_golden_vectors(vectors_dir: Path, load_json: Callable[[Path], Any]) -> None:
    vectors = load_json(vectors_dir / "canonical-json.json")
    for case in vectors["cases"]:
        assert serialize(loads(case["input"])) == case["canonical"], case["input"]
    for rejected in vectors["rejected"]:
        with pytest.raises(ValueError, match="2\\^53"):
            serialize(loads(rejected))


def test_rejects_non_finite_numbers() -> None:
    with pytest.raises(ValueError, match="non-finite"):
        loads("[NaN]")
    with pytest.raises(ValueError, match="finite"):
        format_number(float("inf"))


def test_hash_ignores_member_order() -> None:
    assert sha256({"a": 1, "b": [0.5]}) == sha256({"b": [0.5], "a": 1})
