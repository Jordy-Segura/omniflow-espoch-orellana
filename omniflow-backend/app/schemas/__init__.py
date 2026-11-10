"""Esquemas Pydantic. Los JSON usan camelCase para coincidir con la app Android (kotlinx.serialization)."""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, from_attributes=True)


# ------------------------------------------------------------------ salud
class HealthOut(ApiModel):
    status: str = "ok"
    version: str = "0.1.0"
    database: str = "ok"
    oasis_configured: bool = False
    vision_available: bool = False


# ------------------------------------------------------------------ laboratorios
class LabOut(ApiModel):
    """Contrato de `GET /api/v1/labs` (LabDto en la app)."""

    code: str
    name: str
    building: str = ""
    floor: str = ""
    capacity: int = 0


class LabIn(ApiModel):
    code: str = Field(min_length=2, max_length=32, pattern=r"^[A-Za-z0-9_-]+$")
    name: str = Field(min_length=2, max_length=120)
    building: str = ""
    floor: str = ""
    capacity: int = Field(ge=1, le=1000)


class ScheduleSlotIn(ApiModel):
    subject: str = Field(min_length=1, max_length=160)
    teacher: str = ""
    parallel: str = ""
    day_of_week: int = Field(ge=1, le=7)
    start_minute: int = Field(ge=0, lt=24 * 60)
    end_minute: int = Field(gt=0, le=24 * 60)
    enrolled: int = Field(default=0, ge=0)


class ScheduleSlotOut(ScheduleSlotIn):
    id: int
    source: str
    career_code: str = ""
    level: int = 0
    group: str = ""
    subject_code: str = ""
    period_code: str = ""


class ScheduleEntryOut(ScheduleSlotOut):
    """Bloque de horario con su laboratorio (GET /api/v1/schedule, lo consume la app)."""

    lab_code: str


class PersonOut(ApiModel):
    cedula: str
    nombres: str
    apellidos: str
    email: str = ""
    role: str
    codigo: str = ""
    career_code: str = ""
    level: int = 0


class SyncRunOut(ApiModel):
    id: int
    status: str
    started_at: str
    finished_at: str | None
    report: dict


class AppReleaseOut(ApiModel):
    version_code: int
    version_name: str
    notes: str = ""
    sha256: str
    size: int
    published_at: str
    download_url: str


class LabStatusOut(ApiModel):
    code: str
    name: str
    capacity: int
    status: str
    occupancy: int | None
    last_update: int | None
    current_class: ScheduleSlotOut | None
    next_class: ScheduleSlotOut | None
    minutes_to_next: int | None


# ------------------------------------------------------------------ ocupacion
class OccupancyEventIn(ApiModel):
    """Contrato de `POST /api/v1/occupancy-events/batch` (OccupancyEventDto en la app)."""

    lab_code: str
    timestamp: int = Field(description="Epoch en milisegundos")
    count: int = Field(ge=0, le=10_000)
    entries: int = Field(default=0, ge=0)
    exits: int = Field(default=0, ge=0)
    source: str = "CAMERA_PHONE"
    device_model: str = ""


class BatchResultOut(ApiModel):
    accepted: int = 0
    duplicates: int = 0
    rejected: int = 0
    unknown_labs: list[str] = []


class OccupancyPoint(ApiModel):
    timestamp: int
    count: int
    source: str


class AttendanceStudentOut(ApiModel):
    cedula: str
    nombres: str
    apellidos: str
    email: str = ""
    status: str
    method: str = ""
    first_seen: int | None = None
    confidence: float = 0.0
    face_enrolled: bool = False


class ClassSummaryOut(ApiModel):
    slot_id: int
    lab_code: str
    subject: str
    teacher: str
    parallel: str
    career_code: str
    level: int
    date: str
    start_minute: int
    end_minute: int
    state: str
    total: int
    present: int
    late: int
    absent: int
    pending: int
    justified: int
    faces_enrolled: int
    people_detected: int | None = None


class ClassAttendanceOut(ClassSummaryOut):
    students: list[AttendanceStudentOut]


class AttendanceMarkIn(ApiModel):
    status: str


class RecognizedOut(ApiModel):
    cedula: str
    nombre: str
    similitud: float
    estado: str
    caja: list[int]


class FrameResultOut(ApiModel):
    lab_code: str
    slot_id: int | None
    subject: str | None
    faces: int
    recognized: list[RecognizedOut]
    unknown: int
    policy: str


class EnrollIn(ApiModel):
    cedula: str | None = None
    consent: bool
    images: list[str]  # JPEG en base64


class EnrollOut(ApiModel):
    cedula: str
    added: int
    templates: int
    problems: list[str]


class FaceStatusOut(ApiModel):
    cedula: str
    templates: int
    consent_at: str | None = None


class ChangePasswordIn(ApiModel):
    current_password: str
    new_password: str


class MyAttendanceOut(ApiModel):
    slot_id: int
    lab_code: str
    subject: str
    date: str
    start_minute: int
    status: str
    method: str = ""


class ForecastOut(ApiModel):
    horizon_minutes: int
    predicted: int
    trend_per_hour: float
    r2: float
    samples: int


# ------------------------------------------------------------------ alertas
class AlertOut(ApiModel):
    id: int
    lab_code: str
    lab_name: str
    type: str
    title: str
    message: str
    timestamp: int
    read: bool


# ------------------------------------------------------------------ autenticacion
class LoginIn(ApiModel):
    username: str = Field(min_length=1, max_length=120)
    password: str = Field(default="", max_length=200)
    demo_role: str | None = Field(default=None, description="Solo modo demostracion")


class UserOut(ApiModel):
    username: str
    full_name: str
    email: str = ""
    role: str
    cedula: str | None = None
    career_code: str | None = None
    source: str
    must_change_password: bool = False


class TokenOut(ApiModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int
    user: UserOut


# ------------------------------------------------------------------ camaras
class CameraIn(ApiModel):
    lab_code: str
    name: str = Field(min_length=1, max_length=120)
    url: str = Field(min_length=1, max_length=500, description="rtsp://, http(s):// MJPEG, ruta de video o indice de webcam")
    enabled: bool = False
    counting_line: bool = False
    line_position: float = Field(default=0.5, ge=0.05, le=0.95)
    down_is_entry: bool = True


class CameraOut(CameraIn):
    id: int


class CameraStatusOut(ApiModel):
    id: int
    running: bool
    connected: bool
    people: int
    entries: int
    exits: int
    fps: float
    inference_ms: float
    last_error: str | None
    model: str


# ------------------------------------------------------------------ reportes
class LabAttendanceOut(ApiModel):
    lab_code: str
    lab_name: str
    attendance_pct: int
    avg_occupancy_pct: int
    peak: int


class LateArrivalOut(ApiModel):
    lab_name: str
    subject: str
    teacher: str
    date: str
    minutes_late: int


class WeeklyReportOut(ApiModel):
    days: int
    avg_occupancy_pct: int
    total_uses: int
    classes_registered: int
    alerts: int
    by_weekday: list[int]
    per_lab: list[LabAttendanceOut]
    late_arrivals: list[LateArrivalOut]
    early_exits: int
