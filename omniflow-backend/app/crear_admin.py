"""Crea o restablece una cuenta de administrador o coordinador de OmniFlow.

Uso (desde la carpeta omniflow-backend):
    .venv\\Scripts\\python -m app.crear_admin

La contrasena se escribe sin que se vea en pantalla y nunca se guarda en texto plano.
"""
from __future__ import annotations

import getpass
import sys

from sqlalchemy import func, select

from app import database
from app.models import AppUser
from app.services.users import hash_password, password_problem


def main() -> int:
    import app.models  # noqa: F401 - registra las tablas

    database.Base.metadata.create_all(database.engine)
    email = input("Usuario (correo): ").strip().lower()
    if len(email) < 3 or " " in email:
        print("Usuario no válido.")
        return 1
    rol = input("Rol [A]dministrador / [C]oordinador (A): ").strip().upper() or "A"
    role = "COORDINADOR" if rol.startswith("C") else "ADMINISTRADOR"
    nombre = input("Nombre para mostrar (Administrador): ").strip() or ("Coordinación" if role == "COORDINADOR" else "Administrador")
    pw = getpass.getpass("Contraseña (mín. 8, letras y números): ")
    if getpass.getpass("Repite la contraseña: ") != pw:
        print("Las contraseñas no coinciden.")
        return 1
    problem = password_problem(pw, "")
    if problem:
        print(problem)
        return 1
    with database.SessionLocal() as db:
        u = db.scalar(select(AppUser).where(func.lower(AppUser.email) == email))
        if u is None:
            u = AppUser(email=email, source="MANUAL")
            db.add(u)
        u.full_name, u.role, u.active = nombre, role, True
        u.password_hash, u.must_change_password = hash_password(pw), False
        db.commit()
    print(f"Listo: {email} ({role}). Ya puedes iniciar sesión en la app.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
