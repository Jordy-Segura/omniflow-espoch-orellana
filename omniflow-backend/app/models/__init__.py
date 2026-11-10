"""Modelos de base de datos (SQLAlchemy 2.0)."""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Lab(Base):
    __tablename__ = "labs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    code: Mapped[str] = mapped_column(String(32), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(120))
    building: Mapped[str] = mapped_column(String(80), default="")
    floor: Mapped[str] = mapped_column(String(40), default="")
    capacity: Mapped[int] = mapped_column(Integer, default=30)
    active: Mapped[bool] = mapped_column(Boolean, default=True)

    schedule: Mapped[list[ScheduleSlot]] = relationship(back_populates="lab", cascade="all, delete-orphan")
    cameras: Mapped[list[Camera]] = relationship(back_populates="lab", cascade="all, delete-orphan")


class ScheduleSlot(Base):
    """Bloque semanal. day_of_week: 1 = lunes ... 7 = domingo. Minutos desde 00:00."""

    __tablename__ = "schedule_slots"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    lab_id: Mapped[int] = mapped_column(ForeignKey("labs.id", ondelete="CASCADE"), index=True)
    subject: Mapped[str] = mapped_column(String(160))
    teacher: Mapped[str] = mapped_column(String(160), default="")
    parallel: Mapped[str] = mapped_column(String(40), default="")
    day_of_week: Mapped[int] = mapped_column(Integer)
    start_minute: Mapped[int] = mapped_column(Integer)
    end_minute: Mapped[int] = mapped_column(Integer)
    enrolled: Mapped[int] = mapped_column(Integer, default=0)
    source: Mapped[str] = mapped_column(String(16), default="LOCAL")  # LOCAL | DEMO | PDF | OASIS
    # Datos academicos (OASIS)
    career_code: Mapped[str] = mapped_column(String(16), default="")   # TI, IAMB, ...
    level: Mapped[int] = mapped_column(Integer, default=0)              # 1..10
    group: Mapped[str] = mapped_column(String(16), default="")          # etiqueta del PDF: TI03, AMBI01
    bloque: Mapped[str] = mapped_column(String(40), default="")
    subject_code: Mapped[str] = mapped_column(String(24), default="")  # codigo de materia en OASIS
    teacher_cedula: Mapped[str] = mapped_column(String(16), default="")
    teacher_email: Mapped[str] = mapped_column(String(120), default="")
    teacher_hint: Mapped[str] = mapped_column(String(160), default="")  # docente escrito en el PDF
    period_code: Mapped[str] = mapped_column(String(16), default="")

    lab: Mapped[Lab] = relationship(back_populates="schedule")
    students: Mapped[list[SlotStudent]] = relationship(cascade="all, delete-orphan")


class Person(Base):
    """Docente o estudiante sincronizado desde OASIS."""

    __tablename__ = "people"

    cedula: Mapped[str] = mapped_column(String(16), primary_key=True)
    nombres: Mapped[str] = mapped_column(String(120), default="")
    apellidos: Mapped[str] = mapped_column(String(120), default="")
    email: Mapped[str] = mapped_column(String(160), default="")
    role: Mapped[str] = mapped_column(String(16))  # DOCENTE | ESTUDIANTE
    codigo: Mapped[str] = mapped_column(String(24), default="")
    career_code: Mapped[str] = mapped_column(String(16), default="")
    level: Mapped[int] = mapped_column(Integer, default=0)
    synced_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    @property
    def full_name(self) -> str:
        return f"{self.apellidos} {self.nombres}".strip()


class SlotStudent(Base):
    """Estudiantes inscritos (segun OASIS) en la materia/paralelo de un bloque de laboratorio."""

    __tablename__ = "slot_students"

    slot_id: Mapped[int] = mapped_column(ForeignKey("schedule_slots.id", ondelete="CASCADE"), primary_key=True)
    cedula: Mapped[str] = mapped_column(ForeignKey("people.cedula", ondelete="CASCADE"), primary_key=True)


class OccupancyEvent(Base):
    """Muestra de ocupacion enviada por un telefono o por el modulo de vision del backend."""

    __tablename__ = "occupancy_events"
    __table_args__ = (
        UniqueConstraint("lab_id", "timestamp_ms", "device", name="uq_event_lab_ts_device"),
        Index("ix_event_lab_ts", "lab_id", "timestamp_ms"),
    )

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    lab_id: Mapped[int] = mapped_column(ForeignKey("labs.id", ondelete="CASCADE"))
    timestamp_ms: Mapped[int] = mapped_column(BigInteger)
    count: Mapped[int] = mapped_column(Integer)
    entries: Mapped[int] = mapped_column(Integer, default=0)
    exits: Mapped[int] = mapped_column(Integer, default=0)
    hour_of_week: Mapped[int] = mapped_column(Integer, index=True)
    source: Mapped[str] = mapped_column(String(24))  # CAMERA_PHONE | CAMERA_IP | BACKEND_VISION | DEMO
    device: Mapped[str] = mapped_column(String(120), default="")
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ClassSession(Base):
    """Una clase concreta (bloque de horario + fecha) observada por la camara."""

    __tablename__ = "class_sessions"
    __table_args__ = (UniqueConstraint("slot_id", "epoch_day", name="uq_session_slot_day"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    lab_id: Mapped[int] = mapped_column(ForeignKey("labs.id", ondelete="CASCADE"), index=True)
    slot_id: Mapped[int] = mapped_column(ForeignKey("schedule_slots.id", ondelete="CASCADE"))
    epoch_day: Mapped[int] = mapped_column(Integer, index=True)
    first_presence_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    last_presence_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    peak_count: Mapped[int] = mapped_column(Integer, default=0)
    sum_count: Mapped[int] = mapped_column(BigInteger, default=0)
    samples: Mapped[int] = mapped_column(Integer, default=0)
    ended_early: Mapped[bool] = mapped_column(Boolean, default=False)


class Alert(Base):
    __tablename__ = "alerts"
    __table_args__ = (Index("ix_alert_lab_type_ts", "lab_id", "type", "timestamp_ms"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    lab_id: Mapped[int] = mapped_column(ForeignKey("labs.id", ondelete="CASCADE"))
    type: Mapped[str] = mapped_column(String(32))
    message: Mapped[str] = mapped_column(Text)
    timestamp_ms: Mapped[int] = mapped_column(BigInteger, index=True)
    read: Mapped[bool] = mapped_column(Boolean, default=False)

    lab: Mapped[Lab] = relationship()


class Camera(Base):
    """Camara procesada en el servidor (IP RTSP/MJPEG, o un telefono con IP Webcam)."""

    __tablename__ = "cameras"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    lab_id: Mapped[int] = mapped_column(ForeignKey("labs.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(120))
    url: Mapped[str] = mapped_column(String(500))
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    counting_line: Mapped[bool] = mapped_column(Boolean, default=False)
    line_position: Mapped[float] = mapped_column(Float, default=0.5)
    down_is_entry: Mapped[bool] = mapped_column(Boolean, default=True)

    lab: Mapped[Lab] = relationship(back_populates="cameras")


class AppUser(Base):
    """Cuenta propia de OmniFlow (igual que assistant-grades: login sin depender de credenciales de OASIS).

    Docentes y estudiantes se crean desde la sincronizacion con OASIS con clave inicial = cedula (solo digitos)
    y deben cambiarla en el primer ingreso.
    """

    __tablename__ = "app_users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(160), unique=True, index=True)
    cedula: Mapped[str] = mapped_column(String(16), index=True, default="")
    full_name: Mapped[str] = mapped_column(String(200), default="")
    role: Mapped[str] = mapped_column(String(16))  # ADMINISTRADOR | COORDINADOR | DOCENTE | ESTUDIANTE
    password_hash: Mapped[str] = mapped_column(String(200), default="")
    must_change_password: Mapped[bool] = mapped_column(Boolean, default=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    source: Mapped[str] = mapped_column(String(16), default="OASIS")  # OASIS | MANUAL
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class FaceTemplate(Base):
    """Plantilla facial: SOLO el vector (embedding SFace de 128 dimensiones), nunca la foto.

    Dato biometrico (dato sensible, LOPDP Ecuador): requiere consentimiento y se puede eliminar.
    """

    __tablename__ = "face_templates"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    cedula: Mapped[str] = mapped_column(ForeignKey("people.cedula", ondelete="CASCADE"), index=True)
    embedding: Mapped[str] = mapped_column(Text)  # JSON con 128 floats normalizados (L2)
    quality: Mapped[float] = mapped_column(Float, default=0.0)  # score del detector
    consent_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    enrolled_by: Mapped[str] = mapped_column(String(160), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class AttendanceRecord(Base):
    """Asistencia de un estudiante a una clase concreta (bloque de horario + fecha).

    Un estudiante sin registro en una clase ya terminada = AUSENTE (se calcula, no se guarda).
    """

    __tablename__ = "attendance"
    __table_args__ = (UniqueConstraint("slot_id", "epoch_day", "cedula", name="uq_attendance"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    slot_id: Mapped[int] = mapped_column(ForeignKey("schedule_slots.id", ondelete="CASCADE"), index=True)
    epoch_day: Mapped[int] = mapped_column(Integer, index=True)
    cedula: Mapped[str] = mapped_column(ForeignKey("people.cedula", ondelete="CASCADE"))
    status: Mapped[str] = mapped_column(String(12))  # PRESENTE | ATRASO | AUSENTE | JUSTIFICADO
    method: Mapped[str] = mapped_column(String(12))  # ROSTRO | MANUAL
    first_seen_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    last_seen_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    detections: Mapped[int] = mapped_column(Integer, default=0)
    updated_by: Mapped[str] = mapped_column(String(160), default="")


class SyncRun(Base):
    """Historial de sincronizaciones con OASIS."""

    __tablename__ = "sync_runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    status: Mapped[str] = mapped_column(String(16), default="RUNNING")  # RUNNING | OK | ERROR
    report: Mapped[str] = mapped_column(Text, default="")


class AuditLog(Base):
    """Trazabilidad de acciones (Documento Maestro: Auditoria)."""

    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    username: Mapped[str] = mapped_column(String(120))
    action: Mapped[str] = mapped_column(String(80))
    detail: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)
