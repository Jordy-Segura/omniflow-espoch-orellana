import os
from datetime import datetime
from zoneinfo import ZoneInfo

# Configuracion de pruebas ANTES de importar la app.
os.environ.setdefault("JWT_SECRET", "test-secret-omniflow-0123456789abcdef")
os.environ["VISION_ENABLED"] = "false"
os.environ["SEED_SCHEDULE"] = "false"
os.environ["OASIS_SYNC_ON_START"] = "false"
os.environ["OASIS_WS_USER"] = ""
os.environ["OASIS_WS_PASSWORD"] = ""
os.environ["DEVICE_API_KEY"] = ""
os.environ["DATABASE_URL"] = "sqlite:///./.pytest_omniflow.db"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import database  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.main import app  # noqa: E402

TZ = ZoneInfo("America/Guayaquil")


def ms(y, mo, d, h, mi) -> int:
    """Epoch en ms para una hora local de Ecuador."""
    return int(datetime(y, mo, d, h, mi, tzinfo=TZ).timestamp() * 1000)


@pytest.fixture
def client(tmp_path):
    get_settings().releases_dir = str(tmp_path / "releases")
    database.configure(f"sqlite:///{tmp_path / 'test.db'}")
    with TestClient(app) as c:
        yield c
    database.engine.dispose()


@pytest.fixture
def settings():
    s = get_settings()
    snapshot = s.model_dump()
    yield s
    for k, v in snapshot.items():
        setattr(s, k, v)


def token_for(client: TestClient, role: str = "COORDINADOR", name: str = "Prueba") -> dict[str, str]:
    r = client.post("/api/v1/auth/login", json={"username": name, "demoRole": role})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['accessToken']}"}


def real_token(client: TestClient, role: str = "COORDINADOR", email: str | None = None) -> dict[str, str]:
    """Token de una cuenta propia (no de demostracion): necesaria para las acciones que cambian datos."""
    from app.models import AppUser
    from app.services.users import hash_password
    email = email or f"{role.lower()}.real@omniflow.local"
    with database.SessionLocal() as db:
        if db.query(AppUser).filter_by(email=email).first() is None:
            db.add(AppUser(email=email, full_name=role.title(), role=role, password_hash=hash_password("Clave2026x"),
                           must_change_password=False, source="MANUAL"))
            db.commit()
    r = client.post("/api/v1/auth/login", json={"username": email, "password": "Clave2026x"})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['accessToken']}"}


@pytest.fixture
def auth(client):
    return real_token(client)
