from __future__ import annotations

import json
import os
import uuid
from pathlib import Path


def settings_path() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home()))
    return base / "OsmoBLECtrl" / "settings.json"


def persistent_identifier(path: Path | None = None) -> str:
    path = path or settings_path()
    try:
        value = json.loads(path.read_text(encoding="utf-8"))["pairing_identifier"]
        if isinstance(value, str) and len(value) == 32:
            int(value, 16)
            return value
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        pass
    value = uuid.uuid4().hex
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"pairing_identifier": value}, indent=2), encoding="utf-8")
    return value

