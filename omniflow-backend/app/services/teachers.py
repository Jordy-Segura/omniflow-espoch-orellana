"""Gestion de docentes por administradores y coordinadores: cuenta (usuario y contrasena) y materias asignadas."""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import AppUser, Person, ScheduleSlot, TeacherAssignment
from app.services.oasis_sync import title


def _set_teacher(slot: ScheduleSlot, person: Person) -> None:
    slot.teacher_cedula = person.cedula
    slot.teacher_email = person.email or ""
    slot.teacher = title(f"{person.nombres} {person.apellidos}")


def _clear_teacher(slot: ScheduleSlot, a: TeacherAssignment | None = None) -> None:
    """Quita al docente del bloque y, si se conoce, devuelve el que tenia antes de la asignacion."""
    if a is not None and (a.prev_teacher or a.prev_cedula):
        slot.teacher, slot.teacher_cedula, slot.teacher_email = a.prev_teacher, a.prev_cedula, a.prev_email
        return
    slot.teacher_cedula, slot.teacher_email = "", ""
    slot.teacher = title(slot.teacher_hint) if slot.teacher_hint else ""


def apply_assignments(db: Session) -> int:
    """Vuelve a aplicar los docentes asignados a mano (la sincronizacion con OASIS no los pisa)."""
    n = 0
    for a in db.scalars(select(TeacherAssignment)):
        slot, person = db.get(ScheduleSlot, a.slot_id), db.get(Person, a.cedula)
        if slot is not None and person is not None:
            _set_teacher(slot, person)
            n += 1
    db.flush()
    return n


def assign_slots(db: Session, person: Person, slot_ids: list[int], by: str) -> None:
    """Deja al docente exactamente con estos bloques (un bloque tiene un solo docente)."""
    wanted = set(slot_ids)
    for a in list(db.scalars(select(TeacherAssignment).where(TeacherAssignment.cedula == person.cedula))):
        if a.slot_id not in wanted:
            slot = db.get(ScheduleSlot, a.slot_id)
            if slot is not None and slot.teacher_cedula == person.cedula:
                _clear_teacher(slot, a)
            db.delete(a)
    db.flush()
    # Bloques que OASIS le habia dado y que ya no le corresponden.
    for slot in db.scalars(select(ScheduleSlot).where(ScheduleSlot.teacher_cedula == person.cedula)):
        if slot.id not in wanted:
            _clear_teacher(slot)
    for sid in wanted:
        slot = db.get(ScheduleSlot, sid)
        a = db.get(TeacherAssignment, sid)
        if a is None:
            prev = (slot.teacher, slot.teacher_cedula, slot.teacher_email) if slot.teacher_cedula != person.cedula else ("", "", "")
            db.add(TeacherAssignment(slot_id=sid, cedula=person.cedula, assigned_by=by,
                                     prev_teacher=prev[0], prev_cedula=prev[1], prev_email=prev[2]))
        else:
            a.cedula, a.assigned_by = person.cedula, by
        _set_teacher(slot, person)
    db.flush()


def teacher_slots(db: Session, cedula: str) -> list[ScheduleSlot]:
    return list(db.scalars(select(ScheduleSlot).where(ScheduleSlot.teacher_cedula == cedula)
                           .order_by(ScheduleSlot.day_of_week, ScheduleSlot.start_minute)))


def account_for(db: Session, cedula: str) -> AppUser | None:
    return db.scalar(select(AppUser).where(AppUser.cedula == cedula).order_by(AppUser.active.desc(), AppUser.id))
