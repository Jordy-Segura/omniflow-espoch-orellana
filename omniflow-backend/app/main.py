"""API OmniFlow (FastAPI): integracion, logica de negocio, vision computacional y tiempo real."""
from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import database
from app.config import get_settings
from app.database import Base
from app.routers import alerts, app_updates, attendance, auth, cameras, health, labs, occupancy, reports, schedule, ws
from app.services.seed import seed
from app.vision import vision_manager
from app.websocket import manager

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("omniflow")


def init_db() -> None:
    import app.models  # noqa: F401 - registra las tablas

    Base.metadata.create_all(database.engine)
    s = get_settings()
    with database.SessionLocal() as db:
        seed(db, labs=s.seed_labs, schedule=s.seed_schedule)


@asynccontextmanager
async def lifespan(_: FastAPI):
    s = get_settings()
    if s.jwt_secret_generated:
        log.warning("JWT_SECRET no configurado (o < 16 caracteres): se generó uno temporal; "
                    "los tokens se invalidan al reiniciar")
    init_db()
    manager.bind_loop(asyncio.get_running_loop())
    if s.oasis_sync_on_start and s.seed_schedule:
        # Completa docentes, estudiantes e inscritos desde OASIS sin bloquear el arranque.
        from sqlalchemy import select

        from app.models import SyncRun
        from app.services import oasis_sync

        with database.SessionLocal() as db:
            already = db.scalar(select(SyncRun.id).where(SyncRun.status == "OK").limit(1))
        if already is None:
            oasis_sync.start_background(database.SessionLocal)
    if s.vision_enabled:
        started = await asyncio.to_thread(vision_manager.start_enabled)
        log.info("Cámaras iniciadas en el servidor: %s", started)
    yield
    vision_manager.stop_all()


def create_app() -> FastAPI:
    s = get_settings()
    app = FastAPI(
        title=s.app_name,
        version="0.1.0",
        description="Backend de OmniFlow App (ESPOCH): asistencia, monitoreo y analítica de laboratorios.",
        lifespan=lifespan,
    )
    origins = [o.strip() for o in s.cors_origins.split(",") if o.strip()]
    app.add_middleware(CORSMiddleware, allow_origins=origins, allow_methods=["*"], allow_headers=["*"],
                       allow_credentials="*" not in origins)
    for r in (health, auth, labs, schedule, attendance, occupancy, alerts, reports, cameras, app_updates, ws):
        app.include_router(r.router)
    return app


app = create_app()
