"""Datos iniciales: los 3 laboratorios de computo y su horario oficial (PDF del periodo P0046).

Los docentes y estudiantes se completan luego desde OASIS (`services/oasis_sync.py`).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Lab, ScheduleSlot
from app.repositories import LabRepository

DATA_FILE = Path(__file__).resolve().parent.parent / "data" / "horario_laboratorios_P0046.json"
_GROUP = re.compile(r"^([A-Z]+?)(\d{1,2})$")


def load_schedule_file(path: Path = DATA_FILE) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _minutes(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def parse_group(group: str, careers: dict[str, str]) -> tuple[str, int]:
    """'TI03' -> ('TI', 3); 'AMBI05' -> ('IAMB', 5) usando el mapa de carreras del archivo."""
    m = _GROUP.match(group.strip().upper())
    if not m:
        return "", 0
    prefix, level = m.group(1), int(m.group(2))
    return careers.get(prefix, prefix), level


def seed(db: Session, labs: bool = True, schedule: bool = True, data: dict | None = None) -> None:
    """Idempotente: crea los laboratorios que falten y carga el horario del PDF en los que no tienen bloques."""
    if not labs:
        return
    repo = LabRepository(db)
    data = data or load_schedule_file()
    careers = data.get("careers", {})
    for lab_data in data["labs"]:
        lab = repo.by_code(lab_data["code"]) or repo.add(Lab(
            code=lab_data["code"], name=lab_data["name"], capacity=lab_data.get("capacity", 30),
            building=lab_data.get("building", ""), floor=lab_data.get("floor", "")))
        lab.building = lab_data.get("building", lab.building)
        existing = list(db.scalars(select(ScheduleSlot).where(ScheduleSlot.lab_id == lab.id)))
        for s in existing:  # p. ej. corregir el mapeo de carreras (Sede Orellana) en bases ya creadas
            if s.source == "PDF" and s.group:
                career, level = parse_group(s.group, careers)
                if s.career_code != career:
                    s.career_code, s.level = career, level
        if not schedule or existing:
            continue
        for b in lab_data["blocks"]:
            career, level = parse_group(b.get("group", ""), careers)
            db.add(ScheduleSlot(
                lab_id=lab.id, subject=b["subject"], teacher=b.get("teacherHint", "").title(),
                parallel="", day_of_week=b["day"], start_minute=_minutes(b["start"]), end_minute=_minutes(b["end"]),
                enrolled=0, source="PDF", career_code=career, level=level, group=b.get("group", ""),
                bloque=b.get("bloque", ""), teacher_hint=b.get("teacherHint", ""), period_code=data.get("period", ""),
            ))
    db.commit()
