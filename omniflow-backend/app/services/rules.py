"""Logica de negocio pura (Documento Maestro, pag. 4). Equivalente a `LabRules.kt` de la app."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Protocol

PROXIMO_MINUTES = 30
TEACHER_GRACE_MINUTES = 15
EARLY_EXIT_MINUTES = 15
ACADEMIC_START = 7 * 60
ACADEMIC_END = 21 * 60


class LabStatus(str, Enum):
    LIBRE = "LIBRE"
    OCUPADO = "OCUPADO"
    PROXIMO = "PROXIMO"
    FUERA_DE_HORARIO = "FUERA_DE_HORARIO"
    ANOMALO = "ANOMALO"


class AlertType(str, Enum):
    SOBREAFORO = "SOBREAFORO"
    FUERA_DE_HORARIO = "FUERA_DE_HORARIO"
    AUSENCIA_DOCENTE = "AUSENCIA_DOCENTE"
    SALIDA_ANTICIPADA = "SALIDA_ANTICIPADA"
    ANOMALIA_ML = "ANOMALIA_ML"
    PROXIMA_CLASE = "PROXIMA_CLASE"


ALERT_TITLES = {
    AlertType.SOBREAFORO: "Sobreaforo detectado",
    AlertType.FUERA_DE_HORARIO: "Uso fuera de horario",
    AlertType.AUSENCIA_DOCENTE: "Ausencia de docente",
    AlertType.SALIDA_ANTICIPADA: "Salida anticipada",
    AlertType.ANOMALIA_ML: "Anomalía de ocupación",
    AlertType.PROXIMA_CLASE: "Próxima clase",
}


class SlotLike(Protocol):
    id: int
    subject: str
    teacher: str
    day_of_week: int
    start_minute: int
    end_minute: int


@dataclass(frozen=True)
class ScheduleContext:
    current: SlotLike | None
    next: SlotLike | None
    minutes_to_next: int | None
    minute_of_day: int
    day_of_week: int


@dataclass(frozen=True)
class SessionInfo:
    first_presence_ms: int | None


@dataclass(frozen=True)
class Trigger:
    type: AlertType
    message: str


def schedule_context(slots: list[SlotLike], now: datetime) -> ScheduleContext:
    day = now.isoweekday()
    minute = now.hour * 60 + now.minute
    today = sorted((s for s in slots if s.day_of_week == day), key=lambda s: s.start_minute)
    current = next((s for s in today if s.start_minute <= minute < s.end_minute), None)
    nxt = next((s for s in today if s.start_minute > minute), None)
    return ScheduleContext(current, nxt, (nxt.start_minute - minute) if nxt else None, minute, day)


def outside_academic_hours(ctx: ScheduleContext) -> bool:
    return ctx.day_of_week == 7 or ctx.minute_of_day < ACADEMIC_START or ctx.minute_of_day >= ACADEMIC_END


def lab_status(capacity: int, ctx: ScheduleContext, occupancy: int | None, anomaly: bool = False) -> LabStatus:
    occ = occupancy or 0
    if anomaly or occ > capacity:
        return LabStatus.ANOMALO
    if ctx.current is not None:
        return LabStatus.OCUPADO
    if occ > 0:
        return LabStatus.ANOMALO if outside_academic_hours(ctx) else LabStatus.OCUPADO
    if ctx.minutes_to_next is not None and ctx.minutes_to_next <= PROXIMO_MINUTES:
        return LabStatus.PROXIMO
    if outside_academic_hours(ctx):
        return LabStatus.FUERA_DE_HORARIO
    return LabStatus.LIBRE


def evaluate(
    capacity: int,
    ctx: ScheduleContext,
    occupancy: int,
    session: SessionInfo | None,
    minutes_empty: int,
) -> list[Trigger]:
    """Reglas basicas: sobreaforo, uso sin clase, ausencia del docente y salida anticipada."""
    out: list[Trigger] = []
    cur = ctx.current

    if occupancy > capacity:
        out.append(Trigger(AlertType.SOBREAFORO, f"Se ha superado el aforo máximo ({occupancy}/{capacity} personas)."))

    starts_soon = ctx.minutes_to_next is not None and ctx.minutes_to_next <= 10
    if cur is None and occupancy > 0 and not starts_soon:
        out.append(Trigger(AlertType.FUERA_DE_HORARIO,
                           f"Se detectaron {occupancy} persona(s) sin clase programada en este horario."))

    if cur is not None:
        since_start = ctx.minute_of_day - cur.start_minute
        to_end = cur.end_minute - ctx.minute_of_day
        nobody_yet = (session is None or session.first_presence_ms is None) and occupancy == 0
        if since_start >= TEACHER_GRACE_MINUTES and nobody_yet:
            out.append(Trigger(AlertType.AUSENCIA_DOCENTE,
                               f"No se ha registrado presencia en la clase {cur.subject} ({cur.teacher}) "
                               f"a {since_start} min de iniciada."))
        if (session is not None and session.first_presence_ms is not None and occupancy == 0
                and minutes_empty >= 5 and to_end > EARLY_EXIT_MINUTES):
            out.append(Trigger(AlertType.SALIDA_ANTICIPADA,
                               f"El laboratorio quedó vacío {to_end} min antes de terminar {cur.subject}."))
    return out


def map_oasis_role(role_names: list[str]) -> str:
    joined = " ".join(role_names).upper()
    if "ADMIN" in joined:
        return "ADMINISTRADOR"
    if any(k in joined for k in ("DIRECTOR", "COORD", "DECAN", "SECRETAR")):
        return "COORDINADOR"
    if "DOCENTE" in joined or "PROFESOR" in joined:
        return "DOCENTE"
    return "ESTUDIANTE"
