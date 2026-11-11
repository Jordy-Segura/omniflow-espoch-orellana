"""Autenticacion JWT, dependencias de rol e inicio de sesion (OASIS o demostracion)."""
from __future__ import annotations

import hmac
import time
from collections.abc import Callable

import jwt
from fastapi import Depends, Header, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import get_settings
from app.integrations.oasis import OasisClient, OasisError, OasisNotConfigured
from app.schemas import UserOut
from app.services.rules import map_oasis_role

ROLES = ("ADMINISTRADOR", "COORDINADOR", "DOCENTE", "ESTUDIANTE")
_bearer = HTTPBearer(auto_error=False)


def create_token(user: UserOut) -> tuple[str, int]:
    s = get_settings()
    exp_seconds = s.jwt_expire_minutes * 60
    now = int(time.time())
    payload = {"sub": user.username, "iat": now, "exp": now + exp_seconds, "user": user.model_dump()}
    return jwt.encode(payload, s.jwt_secret, algorithm=s.jwt_algorithm), exp_seconds


def decode_token(token: str) -> UserOut:
    s = get_settings()
    try:
        payload = jwt.decode(token, s.jwt_secret, algorithms=[s.jwt_algorithm])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Sesión expirada") from None
    except jwt.InvalidTokenError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Token inválido") from None
    return UserOut.model_validate(payload["user"])


def current_user(creds: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> UserOut:
    if creds is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Se requiere autenticación",
                            headers={"WWW-Authenticate": "Bearer"})
    return decode_token(creds.credentials)


def require_roles(*roles: str) -> Callable[[UserOut], UserOut]:
    def dep(user: UserOut = Depends(current_user)) -> UserOut:
        if user.role not in roles:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "No tienes permisos para esta acción")
        return user
    return dep


STAFF = require_roles("ADMINISTRADOR", "COORDINADOR", "DOCENTE")
MANAGERS = require_roles("ADMINISTRADOR", "COORDINADOR")
ADMIN = require_roles("ADMINISTRADOR")


def no_demo(user: UserOut) -> UserOut:
    """El modo demostracion solo consulta: no crea cuentas ni modifica datos reales."""
    if user.source == "DEMO":
        raise HTTPException(status.HTTP_403_FORBIDDEN,
                            "El modo demostración solo permite consultar. Inicia sesión con tu cuenta para hacer cambios.")
    return user


def _real(dep: Callable[..., UserOut]) -> Callable[..., UserOut]:
    def inner(user: UserOut = Depends(dep)) -> UserOut:
        return no_demo(user)
    return inner


#: Igual que MANAGERS / STAFF / current_user, pero sin sesiones de demostracion (acciones que cambian datos).
MANAGERS_REAL = _real(MANAGERS)
STAFF_REAL = _real(STAFF)
USER_REAL = _real(current_user)


def device_key(x_device_key: str | None = Header(default=None)) -> None:
    """Si DEVICE_API_KEY esta configurada, los telefonos/camaras deben enviarla en X-Device-Key."""
    expected = get_settings().device_api_key
    if expected and not hmac.compare_digest(x_device_key or "", expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Clave de dispositivo inválida")


# ---------------------------------------------------------------- inicio de sesion
class LoginError(Exception):
    def __init__(self, message: str, code: int = status.HTTP_401_UNAUTHORIZED) -> None:
        super().__init__(message)
        self.code = code


def login_oasis(username: str, password: str, client: OasisClient | None = None) -> UserOut:
    client = client or OasisClient()
    login = username.strip().split("@")[0]
    try:
        roles = client.autenticar_usuario_carrera(login, password)
    except OasisNotConfigured as e:
        raise LoginError(str(e), status.HTTP_503_SERVICE_UNAVAILABLE) from e
    except OasisError as e:
        # OASIS responde "Referencia a objeto no establecida..." cuando el usuario no existe.
        if "referencia a objeto" in str(e).lower():
            raise LoginError("Usuario o contraseña incorrectos") from e
        raise LoginError(str(e), status.HTTP_502_BAD_GATEWAY) from e
    if not roles:
        raise LoginError("Usuario o contraseña incorrectos")
    try:
        persona = client.get_usuario_facultad(login, password)
    except OasisError:
        persona = None
    role = "ADMINISTRADOR" if login.lower() in get_settings().admin_user_set else map_oasis_role([r.nombre_rol for r in roles])
    full = f"{persona.nombres} {persona.apellidos}".strip() if persona else ""
    return UserOut(
        username=login, full_name=full or login, email=persona.email if persona else "",
        role=role, cedula=persona.cedula if persona else None,
        career_code=roles[0].codigo_carrera or None, source="OASIS",
    )


def login_demo(username: str, role: str | None) -> UserOut:
    if not get_settings().demo_login_enabled:
        raise LoginError("El modo demostración está desactivado", status.HTTP_403_FORBIDDEN)
    r = (role or "COORDINADOR").upper()
    if r not in ROLES:
        raise LoginError(f"Rol inválido. Usa uno de: {', '.join(ROLES)}", status.HTTP_400_BAD_REQUEST)
    name = username.strip() or r.title()
    return UserOut(username=name.lower().replace(" ", "."), full_name=name, role=r, source="DEMO")
