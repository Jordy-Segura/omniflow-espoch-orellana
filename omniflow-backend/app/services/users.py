"""Cuentas propias de OmniFlow (login sin credenciales de servicio de OASIS).

Hash: scrypt N=16384, r=8, p=1, 64 bytes, formato `scrypt$<salt_hex>$<hash_hex>` (compatible con
`crypto.scryptSync` de Node).
"""
from __future__ import annotations

import hashlib
import hmac
import re
import secrets

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import AppUser, Person


def hash_password(plain: str) -> str:
    salt = secrets.token_hex(16)
    h = hashlib.scrypt(plain.encode(), salt=salt.encode(), n=16384, r=8, p=1, dklen=64, maxmem=64 * 1024 * 1024)
    return f"scrypt${salt}${h.hex()}"


def verify_password(plain: str, stored: str) -> bool:
    parts = (stored or "").split("$")
    if len(parts) != 3 or parts[0] != "scrypt":
        return False
    calc = hashlib.scrypt(plain.encode(), salt=parts[1].encode(), n=16384, r=8, p=1, dklen=64, maxmem=64 * 1024 * 1024)
    return hmac.compare_digest(calc.hex(), parts[2])


def digits(s: str) -> str:
    return re.sub(r"\D", "", s or "")


def password_problem(new: str, cedula: str) -> str | None:
    if len(new) < 8:
        return "La contraseña debe tener al menos 8 caracteres"
    if not re.search(r"[A-Za-z]", new) or not re.search(r"\d", new):
        return "La contraseña debe combinar letras y números"
    if cedula and digits(new) == digits(cedula):
        return "La contraseña no puede ser la cédula"
    return None


def check_password(user: AppUser, plain: str) -> bool:
    """Clave inicial (sin hash guardado) = cedula sin guion; despues, scrypt."""
    if not user.password_hash:
        return user.must_change_password and bool(digits(user.cedula)) and digits(plain) == digits(user.cedula) and plain.strip().isdigit()
    return verify_password(plain, user.password_hash)


def find_user(db: Session, login: str) -> AppUser | None:
    """Busca por correo institucional o por cedula (con o sin guion)."""
    login = (login or "").strip().lower()
    if not login:
        return None
    ced = digits(login)
    cond = [AppUser.email == login]
    if len(ced) == 10:
        cond.append(AppUser.cedula == f"{ced[:9]}-{ced[9]}")
        cond.append(AppUser.cedula == ced)
    return db.scalar(select(AppUser).where(or_(*cond), AppUser.active.is_(True)))


def role_for(person_role: str, email: str, cedula: str) -> str:
    s = get_settings()
    keys = {email.lower(), digits(cedula)}
    if keys & s.admin_user_set:
        return "ADMINISTRADOR"
    if keys & {u.strip().lower() for u in s.coordinator_users.split(",") if u.strip()}:
        return "COORDINADOR"
    return person_role


def ensure_accounts(db: Session) -> int:
    """Crea cuentas para docentes y estudiantes sincronizados que aun no tienen.
    Clave inicial = cedula (solo digitos), con cambio obligatorio en el primer ingreso."""
    users = list(db.scalars(select(AppUser)))
    existing = {u.cedula for u in users}
    used_emails = {u.email for u in users}
    created = 0
    for p in db.scalars(select(Person)):
        if p.cedula in existing or not digits(p.cedula):
            continue
        fallback = re.sub(r"[^0-9a-z]", "", p.cedula.lower()) + "@omniflow.local"
        email = (p.email or "").strip().lower() or fallback
        if email in used_emails:
            email = fallback
        if email in used_emails:
            continue
        used_emails.add(email)
        db.add(AppUser(
            email=email, cedula=p.cedula, full_name=f"{p.nombres} {p.apellidos}".strip().title(),
            # Sin hash: la clave inicial es la cedula por regla (se evita calcular cientos de scrypt al sincronizar).
            role=role_for(p.role, email, p.cedula), password_hash="", must_change_password=True, source="OASIS",
        ))
        created += 1
    # Administrador inicial opcional (sin contrasenas en el codigo fuente).
    s = get_settings()
    if s.bootstrap_admin_email and s.bootstrap_admin_password:
        email = s.bootstrap_admin_email.strip().lower()
        if db.scalar(select(AppUser.id).where(AppUser.email == email)) is None:
            db.add(AppUser(email=email, full_name="Administrador", role="ADMINISTRADOR",
                           password_hash=hash_password(s.bootstrap_admin_password),
                           must_change_password=True, source="MANUAL"))
            created += 1
    db.flush()
    return created
