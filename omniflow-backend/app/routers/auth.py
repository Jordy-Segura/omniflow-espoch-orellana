from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import LoginError, create_token, current_user, login_demo, login_oasis
from app.config import get_settings
from app.database import get_db
from app.models import AppUser
from app.repositories import audit
from app.schemas import ChangePasswordIn, LoginIn, TokenOut, UserOut
from app.services.users import check_password, find_user, hash_password, password_problem

router = APIRouter(prefix="/api/v1/auth", tags=["autenticación"])


def _user_out(u: AppUser) -> UserOut:
    return UserOut(username=u.email, full_name=u.full_name or u.email, email=u.email if not u.email.endswith("@omniflow.local") else "",
                   role=u.role, cedula=u.cedula or None, source="OMNIFLOW", must_change_password=u.must_change_password)


@router.post("/login", response_model=TokenOut)
def login(body: LoginIn, db: Session = Depends(get_db)) -> TokenOut:
    """Inicio de sesion, en este orden:
    1. Cuenta propia de OmniFlow (correo institucional o cedula; clave inicial = cedula).
    2. OASIS (Seguridad.asmx), solo si hay credenciales de servicio configuradas.
    3. Modo demostracion (`demoRole`), si esta habilitado."""
    try:
        if body.demo_role is not None:
            user = login_demo(body.username, body.demo_role)
        else:
            if not body.password:
                raise LoginError("Ingresa tu contraseña", 400)
            own = find_user(db, body.username)
            if own is not None:
                if not check_password(own, body.password):
                    raise LoginError("Usuario o contraseña incorrectos")
                user = _user_out(own)
            elif get_settings().oasis_configured:
                user = login_oasis(body.username, body.password)
            else:
                raise LoginError("Usuario o contraseña incorrectos")
    except LoginError as e:
        audit(db, body.username[:120], "LOGIN_FALLIDO", str(e))
        db.commit()
        raise HTTPException(e.code, str(e)) from None
    token, exp = create_token(user)
    audit(db, user.username, "LOGIN", f"{user.source} {user.role}")
    db.commit()
    return TokenOut(access_token=token, expires_in=exp, user=user)


@router.post("/change-password", response_model=TokenOut)
def change_password(body: ChangePasswordIn, db: Session = Depends(get_db), user: UserOut = Depends(current_user)) -> TokenOut:
    own = find_user(db, user.username) if user.source == "OMNIFLOW" else None
    if own is None:
        raise HTTPException(400, "Solo las cuentas de OmniFlow pueden cambiar la contraseña aquí")
    if not check_password(own, body.current_password):
        raise HTTPException(401, "La contraseña actual no es correcta")
    problem = password_problem(body.new_password, own.cedula)
    if problem:
        raise HTTPException(422, problem)
    own.password_hash = hash_password(body.new_password)
    own.must_change_password = False
    audit(db, own.email, "CLAVE_CAMBIADA", "")
    db.commit()
    new_user = _user_out(own)
    token, exp = create_token(new_user)
    return TokenOut(access_token=token, expires_in=exp, user=new_user)


@router.get("/me", response_model=UserOut)
def me(user: UserOut = Depends(current_user)) -> UserOut:
    return user


@router.get("/config")
def auth_config() -> dict:
    s = get_settings()
    return {"oasisConfigured": s.oasis_configured, "demoLoginEnabled": s.demo_login_enabled, "ownAccounts": True}
