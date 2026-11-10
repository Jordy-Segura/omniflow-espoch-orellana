"""Configuracion del backend (variables de entorno o archivo .env)."""
from __future__ import annotations

import secrets
from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = "OmniFlow API"
    environment: str = "development"

    # Base de datos: PostgreSQL en produccion (docker-compose), SQLite para desarrollo/pruebas.
    database_url: str = "sqlite:///./omniflow.db"

    # JWT
    jwt_secret: str = ""
    #: True si no se configuro JWT_SECRET y se genero uno temporal (los tokens no sobreviven reinicios).
    jwt_secret_generated: bool = False
    jwt_algorithm: str = "HS256"
    jwt_expire_minutes: int = 12 * 60

    # OASIS (ESPOCH). Las credenciales del SERVICIO las entrega la DTIC.
    oasis_base_url: str = "http://swoasis.espoch.edu.ec/OASis/OAS_Interop/"
    oasis_ws_user: str = ""
    oasis_ws_password: str = ""
    oasis_timeout_seconds: float = 20.0

    # Inicio de sesion de demostracion (sin OASIS). Desactivar en produccion.
    demo_login_enabled: bool = True
    # Correos o cedulas con rol ADMINISTRADOR / COORDINADOR (separados por coma).
    admin_users: str = ""
    coordinator_users: str = ""
    # Primer administrador (opcional). La clave debe cambiarse en el primer ingreso.
    bootstrap_admin_email: str = ""
    bootstrap_admin_password: str = ""

    # Asistencia automatica (reconocimiento facial)
    attendance_late_minutes: int = 10      # despues de este tiempo desde el inicio -> ATRASO
    attendance_early_minutes: int = 10     # se acepta deteccion desde N min antes del inicio
    face_max_templates: int = 5

    # Clave opcional para que los telefonos/camaras envien eventos (cabecera X-Device-Key).
    device_api_key: str = ""

    # Datos iniciales: 3 laboratorios + horario del PDF del periodo; luego OASIS completa docentes/estudiantes.
    seed_labs: bool = True
    seed_schedule: bool = True
    oasis_sync_on_start: bool = True

    # Actualizaciones de la app Android (APK servida por este backend)
    releases_dir: str = "./releases"
    release_upload_key: str = ""

    # Vision computacional (YOLOv8 + ByteTrack)
    vision_enabled: bool = True
    yolo_model: str = "yolov8n.pt"
    yolo_confidence: float = 0.35
    yolo_imgsz: int = 640
    yolo_device: str = "cpu"
    vision_sample_seconds: int = 10

    timezone: str = "America/Guayaquil"
    cors_origins: str = "*"

    def model_post_init(self, __context: object) -> None:
        if len(self.jwt_secret) < 16:
            self.jwt_secret = secrets.token_urlsafe(48)
            self.jwt_secret_generated = True

    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.timezone)

    @property
    def oasis_configured(self) -> bool:
        """True si hay credenciales de servicio. OASIS responde la mayoria de operaciones sin ellas."""
        return bool(self.oasis_ws_user and self.oasis_ws_password)

    @property
    def admin_user_set(self) -> set[str]:
        return {u.strip().lower() for u in self.admin_users.split(",") if u.strip()}


@lru_cache
def get_settings() -> Settings:
    return Settings()
