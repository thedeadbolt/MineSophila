import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools.schemas import Schemas

TESTS = Path(__file__).resolve().parents[1]
FIXTURES = TESTS / "integration" / "fixtures" / "honey-factory"
SCHEMA_DIR = TESTS.parent / "schemas" / "honey-factory" / "1.0"


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def load_json() -> Callable[[Path], Any]:
    return load


@pytest.fixture(scope="session")
def schema_dir() -> Path:
    return SCHEMA_DIR


@pytest.fixture(scope="session")
def schemas() -> Schemas:
    return Schemas(SCHEMA_DIR)


@pytest.fixture(scope="session")
def contract_dir() -> Path:
    return FIXTURES / "1.0"


@pytest.fixture(scope="session")
def vectors_dir() -> Path:
    return FIXTURES / "vectors"
