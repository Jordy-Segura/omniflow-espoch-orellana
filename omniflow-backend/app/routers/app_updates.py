"""Canal de actualizaciones de la app Android: el backend guarda la ultima APK y la app la descarga sola.

Publicar (lo hace la tarea `./gradlew publishApk` de la app):
    PUT /api/v1/app/releases?versionCode=..&versionName=..&notes=..   (cabecera X-Release-Key, cuerpo = APK)
Consultar / descargar (la app, sin autenticacion):
    GET /api/v1/app/latest
    GET /api/v1/app/download/omniflow-<versionCode>.apk
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse

from app.config import get_settings
from app.schemas import AppReleaseOut

router = APIRouter(prefix="/api/v1/app", tags=["actualizaciones de la app"])

APK_MIME = "application/vnd.android.package-archive"
MAX_APK_BYTES = 300 * 1024 * 1024
KEEP_RELEASES = 5
_FILE = re.compile(r"^omniflow-\d{1,10}\.apk$")


def _dir() -> Path:
    d = Path(get_settings().releases_dir).resolve()
    d.mkdir(parents=True, exist_ok=True)
    return d


def _latest() -> dict | None:
    f = _dir() / "latest.json"
    if not f.exists():
        return None
    return json.loads(f.read_text(encoding="utf-8"))


def _out(meta: dict) -> AppReleaseOut:
    return AppReleaseOut(
        version_code=meta["versionCode"], version_name=meta["versionName"], notes=meta.get("notes", ""),
        sha256=meta["sha256"], size=meta["size"], published_at=meta["publishedAt"],
        download_url=f"/api/v1/app/download/{meta['file']}",
    )


@router.get("/latest", response_model=AppReleaseOut)
def latest() -> AppReleaseOut:
    meta = _latest()
    if meta is None:
        raise HTTPException(404, "Aún no se ha publicado ninguna versión de la app")
    return _out(meta)


@router.get("/download/{file_name}")
def download(file_name: str) -> FileResponse:
    if not _FILE.match(file_name):
        raise HTTPException(404, "Archivo no encontrado")
    path = _dir() / file_name
    if not path.is_file():
        raise HTTPException(404, "Archivo no encontrado")
    return FileResponse(path, media_type=APK_MIME, filename=file_name)


@router.put("/releases", response_model=AppReleaseOut, status_code=201)
async def publish(
    request: Request,
    version_code: int = Query(..., alias="versionCode", ge=1, le=2_100_000_000),
    version_name: str = Query(..., alias="versionName", max_length=40),
    notes: str = Query("", max_length=2000),
    x_release_key: str | None = Header(default=None),
) -> AppReleaseOut:
    expected = get_settings().release_upload_key
    if not expected:
        raise HTTPException(403, "Publicación deshabilitada: configura RELEASE_UPLOAD_KEY en el servidor")
    if not hmac.compare_digest(x_release_key or "", expected):
        raise HTTPException(401, "Clave de publicación inválida")
    current = _latest()
    if current and version_code <= current["versionCode"]:
        raise HTTPException(409, f"versionCode debe ser mayor que {current['versionCode']}")

    data = await request.body()
    if len(data) > MAX_APK_BYTES:
        raise HTTPException(413, "APK demasiado grande")
    if not data.startswith(b"PK"):
        raise HTTPException(400, "El cuerpo no es una APK válida")

    d = _dir()
    name = f"omniflow-{version_code}.apk"
    tmp = d / (name + ".part")
    tmp.write_bytes(data)
    tmp.replace(d / name)
    meta = {
        "versionCode": version_code, "versionName": version_name, "notes": notes, "file": name,
        "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
        "publishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    (d / "latest.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    # Conservar solo las ultimas versiones
    apks = sorted(d.glob("omniflow-*.apk"), key=lambda p: int(p.stem.split("-")[1]), reverse=True)
    for old in apks[KEEP_RELEASES:]:
        old.unlink(missing_ok=True)
    return _out(meta)
