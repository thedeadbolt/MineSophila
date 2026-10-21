import importlib

import pytest

WORKSPACE_PACKAGES = ["fly_vision"]


@pytest.mark.integration
@pytest.mark.parametrize("module", WORKSPACE_PACKAGES)
def test_workspace_package_is_installed(module: str) -> None:
    assert importlib.import_module(module)
