"""Cliente SOAP 1.1 para OASIS (ESPOCH): Seguridad, InfoGeneral e InfoCarrera.

Todos los metodos exigen la cabecera `<credentials>` con el usuario/clave del SERVICIO
(variables OASIS_WS_USER / OASIS_WS_PASSWORD), distintos de las credenciales del estudiante/docente.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

import httpx

from app.config import get_settings

log = logging.getLogger(__name__)
NS = "http://academico.espoch.edu.ec/"
DAYS = ["strLunes", "strMartes", "strMiercoles", "strJueves", "strViernes", "strSabado", "strDomingo"]


class OasisError(Exception):
    pass


class OasisNotConfigured(OasisError):
    pass


@dataclass(frozen=True)
class RolCarrera:
    codigo_carrera: str
    nombre_rol: str


@dataclass(frozen=True)
class Persona:
    cedula: str
    apellidos: str
    nombres: str
    email: str


@dataclass(frozen=True)
class FilaHorario:
    inicio_fin: str
    dias: list[str]


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _find(el: ET.Element, name: str) -> ET.Element | None:
    for node in el.iter():
        if _local(node.tag) == name:
            return node
    return None


def _children(el: ET.Element | None, name: str) -> list[ET.Element]:
    if el is None:
        return []
    return [c for c in el if _local(c.tag) == name]


def _text(el: ET.Element | None, name: str) -> str:
    if el is None:
        return ""
    for c in el:
        if _local(c.tag) == name:
            return (c.text or "").strip()
    return ""


def build_envelope(method: str, params: list[tuple[str, str]], user: str, password: str) -> str:
    body = "".join(f"<tns:{k}>{escape(v)}</tns:{k}>" for k, v in params)
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        f'<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="{NS}">'
        f"<soap:Header><tns:credentials><tns:username>{escape(user)}</tns:username>"
        f"<tns:password>{escape(password)}</tns:password></tns:credentials></soap:Header>"
        f"<soap:Body><tns:{method}>{body}</tns:{method}></soap:Body></soap:Envelope>"
    )


def parse_result(xml: str, method: str) -> ET.Element:
    root = ET.fromstring(xml)
    fault = _find(root, "faultstring")
    if fault is not None:
        raise OasisError(fault.text or "SOAP Fault")
    result = _find(root, f"{method}Result")
    return result if result is not None else ET.Element(f"{method}Result")


class OasisClient:
    def __init__(self, transport: httpx.BaseTransport | None = None) -> None:
        s = get_settings()
        self.base = s.oasis_base_url.rstrip("/")
        self.user = s.oasis_ws_user
        self.password = s.oasis_ws_password
        self._http = httpx.Client(timeout=s.oasis_timeout_seconds, transport=transport)

    @property
    def configured(self) -> bool:
        return bool(self.user and self.password)

    def close(self) -> None:
        self._http.close()

    def call(self, service: str, method: str, params: list[tuple[str, str]]) -> ET.Element:
        # Las credenciales de servicio son opcionales: OASIS responde InfoGeneral/InfoCarrera sin ellas
        # Si existen, se envian en la cabecera.
        try:
            resp = self._http.post(
                f"{self.base}/{service}.asmx",
                content=build_envelope(method, params, self.user, self.password).encode("utf-8"),
                headers={"Content-Type": "text/xml; charset=utf-8", "SOAPAction": f'"{NS}{method}"'},
            )
        except httpx.HTTPError as e:
            raise OasisError(f"No se pudo conectar con OASIS: {e}") from e
        if resp.status_code >= 400:
            try:
                parse_result(resp.text, method)
            except OasisError:
                raise
            except ET.ParseError:
                pass
            raise OasisError(f"OASIS respondió HTTP {resp.status_code}")
        try:
            return parse_result(resp.text, method)
        except ET.ParseError as e:
            raise OasisError("Respuesta de OASIS no es XML válido") from e

    # ---------------------------------------------------------------- Seguridad
    def autenticar_usuario_carrera(self, login: str, password: str) -> list[RolCarrera]:
        r = self.call("Seguridad", "AutenticarUsuarioCarrera", [("login", login), ("password", password)])
        return [RolCarrera(_text(n, "CodigoCarrera"), _text(n, "NombreRol")) for n in _children(r, "RolCarrera")]

    def get_usuario_facultad(self, login: str, password: str) -> Persona | None:
        r = self.call("Seguridad", "GetUsuarioFacultad", [("login", login), ("password", password)])
        if len(r) == 0:
            return None
        return Persona(_text(r, "Cedula"), _text(r, "Apellidos"), _text(r, "Nombres"), _text(r, "Email"))

    # ---------------------------------------------------------------- InfoGeneral
    def get_periodo_actual(self) -> dict[str, str] | None:
        r = self.call("InfoGeneral", "GetPeriodoActual", [])
        if len(r) == 0:
            return None
        return {k: _text(r, k) for k in ("Codigo", "Descripcion", "FechaInicio", "FechaFin")}

    # ---------------------------------------------------------------- InfoCarrera
    def get_horario_docente(self, cod_carrera: str, cedula: str) -> list[FilaHorario]:
        return self._horario("GetHorarioDocente", "HorarioDocente", cod_carrera, cedula)

    def get_horario_estudiante(self, cod_carrera: str, cedula: str) -> list[FilaHorario]:
        return self._horario("GetHorarioEstudiante", "HorarioEstudiante", cod_carrera, cedula)

    def get_todas_carreras(self) -> list[dict]:
        return items(self.call("InfoGeneral", "GetTodasCarreras", []), "UnidadAcademica")

    def get_malla(self, cod_carrera: str) -> list[dict]:
        """Materias del pensum vigente: CodMateria, Materia, CodNivel, ..."""
        r = self.call("InfoCarrera", "GetMallaCurricularPensumVigenteSinDescripcion", [("strCodCarrera", cod_carrera)])
        return items(r, "Materia_Pensum")

    def get_dictados(self, cod_carrera: str, cod_materia: str) -> list[dict]:
        """Paralelos de una materia con su docente: CodNivel, Paralelo, Docente{Cedula, Apellidos, Nombres, Email}."""
        r = self.call("InfoCarrera", "GetDictadosMateria", [("CodCarrera", cod_carrera), ("CodMateria", cod_materia)])
        return items(r, "Dictado_Materia")

    def get_horarios_docente(self, cod_carrera: str, cedula: str, cod_periodo: str) -> list[dict]:
        """Clases del docente en el periodo: CodMateria, Materia, CodDia, Dia, Inicio, Fin (bloques de 1 hora)."""
        r = self.call("InfoCarrera", "GetHorariosDocente", [
            ("strCodCarrera", cod_carrera), ("strCedula", format_cedula(cedula)), ("strCodPeriodo", cod_periodo)])
        return items(r, "HorarioClase")

    def get_todas_matricula(self, cod_carrera: str, cod_periodo: str) -> list[dict]:
        """Estudiantes matriculados en la carrera: Cedula, Nombres, Apellidos, Codigo, CodNivel, CodEstado."""
        r = self.call("InfoCarrera", "GetTodasMatriculaEstudiantes", [
            ("strCodCarrera", cod_carrera), ("strCodPeriodo", cod_periodo)])
        return items(r, "TodasMatriculaEstudiantes")

    def get_alumnos_materia(self, cod_carrera: str, cod_nivel: str, paralelo: str, cod_periodo: str,
                            cod_materia: str) -> list[dict]:
        r = self.call("InfoCarrera", "GetAlumnosMateria", [
            ("strCodCarrera", cod_carrera), ("strCodNivel", cod_nivel), ("strCodParalelo", paralelo),
            ("strCodPeriodo", cod_periodo), ("strCodMateria", cod_materia)])
        return items(r, "Estudiante")

    def _horario(self, method: str, item: str, cod_carrera: str, cedula: str) -> list[FilaHorario]:
        r = self.call("InfoCarrera", method, [("CodCarrera", cod_carrera), ("strCedula", format_cedula(cedula))])
        return [FilaHorario(_text(n, "strInicioFinHora"), [_text(n, d) for d in DAYS]) for n in _children(r, item)]


def format_cedula(cedula: str) -> str:
    """OASIS espera la cedula con guion: 0601234567 -> 060123456-7."""
    digits = re.sub(r"\D", "", cedula or "")
    return f"{digits[:9]}-{digits[9]}" if len(digits) == 10 else (cedula or "")


def to_dict(el: ET.Element) -> dict | str:
    """Convierte un elemento XML a dict (listas cuando un hijo se repite)."""
    children = list(el)
    if not children:
        return (el.text or "").strip()
    out: dict = {}
    for c in children:
        k = _local(c.tag)
        v = to_dict(c)
        if k in out:
            if not isinstance(out[k], list):
                out[k] = [out[k]]
            out[k].append(v)
        else:
            out[k] = v
    return out


def items(el: ET.Element, name: str) -> list[dict]:
    return [d for d in (to_dict(c) for c in _children(el, name)) if isinstance(d, dict)]


_TIME = re.compile(r"(\d{1,2})[:h.](\d{2})")


def parse_time_range(text: str) -> tuple[int, int] | None:
    """'07:00 - 08:00' -> (420, 480). Devuelve None si el formato no se reconoce."""
    found = _TIME.findall(text or "")
    if len(found) < 2:
        return None
    (h1, m1), (h2, m2) = found[0], found[1]
    start, end = int(h1) * 60 + int(m1), int(h2) * 60 + int(m2)
    return (start, end) if 0 <= start < end <= 24 * 60 else None
