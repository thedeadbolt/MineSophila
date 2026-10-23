"""RFC 8785 JSON Canonicalization Scheme, the contract's ``jcs-sha256-v1`` hash input."""

import hashlib
import json
from decimal import Decimal

ALGORITHM = "jcs-sha256-v1"
MAX_SAFE_INTEGER = 2**53 - 1

type Json = bool | int | float | str | list[Json] | dict[str, Json] | None


def loads(text: str) -> Json:
    """Parse strict JSON, rejecting NaN and infinity."""

    def reject(constant: str) -> Json:
        raise ValueError(f"non-finite JSON number {constant}")

    value: Json = json.loads(text, parse_constant=reject)
    return value


def serialize(value: Json) -> str:
    """Return the canonical JSON text of ``value``."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        if abs(value) > MAX_SAFE_INTEGER:
            raise ValueError(f"integer {value} exceeds 2^53 - 1 and cannot be canonicalized")
        return format_number(float(value))
    if isinstance(value, float):
        return format_number(value)
    if isinstance(value, str):
        return _string(value)
    if isinstance(value, list):
        return "[" + ",".join(serialize(item) for item in value) + "]"
    members = sorted(value.items(), key=lambda item: item[0].encode("utf-16-be"))
    return "{" + ",".join(f"{_string(key)}:{serialize(item)}" for key, item in members) + "}"


def sha256(value: Json) -> str:
    """Lowercase hexadecimal SHA-256 of the canonical UTF-8 text."""
    return hashlib.sha256(serialize(value).encode()).hexdigest()


def format_number(value: float) -> str:
    """Format like ECMAScript ``Number.prototype.toString``."""
    if value != value or value in (float("inf"), float("-inf")):
        raise ValueError("JSON numbers must be finite")
    if value == 0:
        return "0"
    sign = "-" if value < 0 else ""
    # repr() gives the shortest decimal that round-trips.
    digits_tuple = Decimal(repr(abs(value))).normalize().as_tuple()
    digits = "".join(str(d) for d in digits_tuple.digits)
    exponent = digits_tuple.exponent
    assert isinstance(exponent, int)
    k = len(digits)
    n = k + exponent
    if k <= n <= 21:
        return sign + digits + "0" * (n - k)
    if 0 < n <= 21:
        return sign + digits[:n] + "." + digits[n:]
    if -6 < n <= 0:
        return sign + "0." + "0" * -n + digits
    e = n - 1
    mantissa = digits[0] + ("." + digits[1:] if k > 1 else "")
    return f"{sign}{mantissa}e{'-' if e < 0 else '+'}{abs(e)}"


_ESCAPES = {
    '"': '\\"',
    "\\": "\\\\",
    "\b": "\\b",
    "\f": "\\f",
    "\n": "\\n",
    "\r": "\\r",
    "\t": "\\t",
}


def _string(value: str) -> str:
    out: list[str] = []
    for char in value:
        if char in _ESCAPES:
            out.append(_ESCAPES[char])
        elif ord(char) < 0x20:
            out.append(f"\\u{ord(char):04x}")
        else:
            out.append(char)
    return '"' + "".join(out) + '"'
