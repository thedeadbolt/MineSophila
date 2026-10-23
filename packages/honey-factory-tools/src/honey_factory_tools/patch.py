"""Apply the small JSON Pointer patches used by shared contract fixtures."""

import copy
from typing import Literal, NotRequired, TypedDict, cast

from honey_factory_tools.canonical import Json


class Operation(TypedDict):
    op: Literal["set", "remove"]
    path: str
    value: NotRequired[Json]


def apply(document: Json, operations: list[Operation]) -> Json:
    """Return a patched copy. ``set`` replaces or adds a member; ``remove`` deletes it."""
    result = copy.deepcopy(document)
    for operation in operations:
        tokens = [_unescape(token) for token in operation["path"].split("/")[1:]]
        parent = result
        for token in tokens[:-1]:
            parent = _child(parent, token)
        last = tokens[-1]
        if isinstance(parent, dict):
            if operation["op"] == "set":
                parent[last] = copy.deepcopy(operation.get("value"))
            else:
                del parent[last]
        elif isinstance(parent, list):
            index = int(last)
            if operation["op"] == "set":
                parent[index] = copy.deepcopy(operation.get("value"))
            else:
                del parent[index]
        else:
            raise KeyError(operation["path"])
    return result


def _child(node: Json, token: str) -> Json:
    if isinstance(node, dict):
        return node[token]
    if isinstance(node, list):
        return cast(list[Json], node)[int(token)]
    raise KeyError(token)


def _unescape(token: str) -> str:
    return token.replace("~1", "/").replace("~0", "~")
