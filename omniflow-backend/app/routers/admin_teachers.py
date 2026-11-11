"""Administracion de docentes (administrador y coordinador): datos, usuario, contrasena y materias asignadas."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.auth import MANAGERS
from app.database import get_db
from app.integrations.oasis import format_cedula
from app.models import AppUser, Lab, Person, ScheduleSlot, SlotStudent, TeacherAssignment
from app.repositories import audit
from app.schemas import TeacherIn, TeacherOut, TeacherSlotOut, UserOut
from app.services.teachers import account_for, assign_slots, teacher_slots
from app.services.users import digits, hash_password, password_problem

router = APIRouter(prefix="/api/v1/admin/teachers", tags=["administración de docentes"])


def _out(db: Session, p: Person) -> TeacherOut:
    acc = account_for(db, p.cedula)
    labs = {lab.id: lab.code for lab in db.scalars(select(Lab))}
    return TeacherOut(
        cedula=p.cedula, nombres=p.nombres, apellidos=p.apellidos, email=p.email,
        username=acc.email if acc else None, has_account=acc is not None, account_active=bool(acc and acc.active),
        must_change_password=bool(acc and acc.must_change_password), source=acc.source if acc else "OASIS",
        slots=[TeacherSlotOut(id=s.id, lab_code=labs.get(s.lab_id, ""), subject=s.subject, day_of_week=s.day_of_week,
                              start_minute=s.start_minute, end_minute=s.end_minute, parallel=s.parallel)
               for s in teacher_slots(db, p.cedula)],
    )


def _validate_slots(db: Session, ids: list[int]) -> None:
    if not ids:
        return
    found = set(db.scalars(select(ScheduleSlot.id).where(ScheduleSlot.id.in_(ids))))
    missing = sorted(set(ids) - found)
    if missing:
        raise HTTPException(422, f"Bloques de horario inexistentes: {missing}")


def _save(db: Session, body: TeacherIn, user: UserOut, cedula: str, creating: bool) -> Person:
    username = body.username.strip().lower()
    clash = db.scalar(select(AppUser).where(func.lower(AppUser.email) == username, AppUser.cedula != cedula))
    if clash is not None:
        raise HTTPException(409, "Ese usuario ya pertenece a otra cuenta")
    _validate_slots(db, body.slot_ids)

    person = db.get(Person, cedula)
    if person is not None and person.role != "DOCENTE":
        raise HTTPException(409, "Esa cédula pertenece a un estudiante")
    if person is None:
        person = Person(cedula=cedula, role="DOCENTE")
        db.add(person)
    person.nombres = body.nombres.strip().upper()
    person.apellidos = body.apellidos.strip().upper()
    person.email = body.email.strip().lower()

    acc = account_for(db, cedula)
    if body.password:
        problem = password_problem(body.password, cedula)
        if problem:
            raise HTTPException(422, problem)
    elif acc is None or (creating and not acc.password_hash):
        raise HTTPException(422, "Asigna una contraseña al docente")
    full_name = f"{person.nombres} {person.apellidos}".title()
    if acc is None:
        acc = AppUser(email=username, cedula=cedula, full_name=full_name, role="DOCENTE", source="MANUAL")
        db.add(acc)
    acc.email, acc.full_name, acc.active = username, full_name, True
    if acc.role not in ("ADMINISTRADOR", "COORDINADOR"):
        acc.role = "DOCENTE"
    if body.password:
        acc.password_hash = hash_password(body.password)
        acc.must_change_password = body.must_change_password
    db.flush()
    assign_slots(db, person, body.slot_ids, user.username)
    return person


@router.get("", response_model=list[TeacherOut])
def list_teachers(db: Session = Depends(get_db), _: UserOut = Depends(MANAGERS)) -> list[TeacherOut]:
    """Docentes (sincronizados desde OASIS o creados a mano) con su cuenta y sus materias."""
    people = db.scalars(select(Person).where(Person.role == "DOCENTE").order_by(Person.apellidos, Person.nombres))
    return [_out(db, p) for p in people]


@router.post("", response_model=TeacherOut, status_code=201)
def create_teacher(body: TeacherIn, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> TeacherOut:
    if len(digits(body.cedula)) != 10:
        raise HTTPException(422, "La cédula debe tener 10 dígitos")
    cedula = format_cedula(body.cedula)
    acc = account_for(db, cedula)
    if acc is not None and acc.source == "MANUAL" and acc.active:
        raise HTTPException(409, "Ese docente ya tiene una cuenta: edítalo en lugar de crearlo")
    person = _save(db, body, user, cedula, creating=True)
    audit(db, user.username, "DOCENTE_CREADO", f"{cedula} {len(body.slot_ids)} materias")
    db.commit()
    return _out(db, person)


@router.put("/{cedula}", response_model=TeacherOut)
def update_teacher(cedula: str, body: TeacherIn, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> TeacherOut:
    cedula = format_cedula(cedula)
    if db.get(Person, cedula) is None:
        raise HTTPException(404, "Docente no encontrado")
    if format_cedula(body.cedula) != cedula:
        raise HTTPException(422, "No se puede cambiar la cédula de un docente")
    person = _save(db, body, user, cedula, creating=False)
    audit(db, user.username, "DOCENTE_EDITADO", f"{cedula} {len(body.slot_ids)} materias")
    db.commit()
    return _out(db, person)


@router.delete("/{cedula}", status_code=204)
def delete_teacher(cedula: str, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> None:
    """Quita las materias y desactiva la cuenta. Un docente creado a mano se elimina por completo."""
    cedula = format_cedula(cedula)
    person = db.get(Person, cedula)
    if person is None or person.role != "DOCENTE":
        raise HTTPException(404, "Docente no encontrado")
    assign_slots(db, person, [], user.username)
    acc = account_for(db, cedula)
    manual = acc is not None and acc.source == "MANUAL"
    if acc is not None:
        if manual:
            db.delete(acc)
        else:
            acc.active = False
    if manual and db.scalar(select(SlotStudent.slot_id).where(SlotStudent.cedula == cedula).limit(1)) is None:
        db.execute(TeacherAssignment.__table__.delete().where(TeacherAssignment.cedula == cedula))
        db.delete(person)
    audit(db, user.username, "DOCENTE_ELIMINADO", cedula)
    db.commit()
