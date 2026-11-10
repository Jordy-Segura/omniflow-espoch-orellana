"""Registro facial desde el PDF de matricula: identifica al estudiante y toma su foto.

1. Se lee el texto de cada pagina (pypdf) y se busca la cedula (`0601234567` o `060123456-7`); si no hay cedula,
   se busca un estudiante sincronizado cuyos nombres y apellidos aparezcan completos en el texto.
2. Se extraen las imagenes embebidas de la pagina; las fotos pequenas se amplian para el detector.
3. Se elige la imagen con exactamente un rostro (la de mayor tamano de rostro) -> embedding SFace -> plantilla.

Un PDF puede traer una matricula (una persona) o varias paginas con distintas personas (carga masiva).
Las paginas sin texto (PDF escaneado) se asignan a la cedula indicada por quien sube el archivo, si la hay.
"""
from __future__ import annotations

import io
import re
import unicodedata
from dataclasses import dataclass, field

import numpy as np
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Person
from app.services import attendance as att
from app.vision.faces import engine

CEDULA = re.compile(r"(?<!\d)(\d{9})\s*-?\s*(\d)(?!\d)")


@dataclass
class PdfEnrollResult:
    page: int
    cedula: str | None
    nombre: str | None
    status: str          # REGISTRADO | SIN_ROSTRO | NO_IDENTIFICADO | NO_AUTORIZADO
    detail: str = ""


@dataclass
class _Page:
    text: str
    images: list[np.ndarray] = field(default_factory=list)


def _norm(s: str) -> str:
    s = unicodedata.normalize("NFD", (s or "").upper()).encode("ascii", "ignore").decode()
    return re.sub(r"[^A-Z0-9 ]+", " ", s)


def _read_pdf(data: bytes) -> list[_Page]:
    from pypdf import PdfReader

    try:
        reader = PdfReader(io.BytesIO(data))
    except Exception as e:  # noqa: BLE001
        raise ValueError("El archivo no es un PDF válido") from e
    if reader.is_encrypted:
        try:
            reader.decrypt("")
        except Exception as e:  # noqa: BLE001
            raise ValueError("El PDF está protegido con contraseña") from e
    pages = []
    for page in reader.pages[:200]:
        text = page.extract_text() or ""
        imgs = [a for a in _page_images(page) if min(a.shape[:2]) >= 40]
        pages.append(_Page(text, imgs))
    return pages


def _page_images(page) -> list[np.ndarray]:
    """Imagenes de la pagina sin depender de PIL (ultralytics reemplaza PIL.Image.open y rompe pypdf)."""
    import cv2

    out = []
    try:
        xobjects = page["/Resources"]["/XObject"].get_object()
    except Exception:  # noqa: BLE001 - pagina sin imagenes
        return out
    for name in xobjects:
        try:
            obj = xobjects[name].get_object()
            if obj.get("/Subtype") != "/Image":
                continue
            filters = obj.get("/Filter")
            filters = [str(f) for f in (filters if isinstance(filters, list) else [filters])] if filters else []
            if "/DCTDecode" in filters or "/JPXDecode" in filters:
                img = cv2.imdecode(np.frombuffer(obj._data, np.uint8), cv2.IMREAD_COLOR)  # JPEG embebido tal cual
            else:
                w, h = int(obj["/Width"]), int(obj["/Height"])
                raw = np.frombuffer(obj.get_data(), np.uint8)
                cs = str(obj.get("/ColorSpace", "/DeviceRGB"))
                ch = 1 if "Gray" in cs else 4 if "CMYK" in cs else 3
                if int(obj.get("/BitsPerComponent", 8)) != 8 or raw.size < w * h * ch:
                    continue
                img = raw[: w * h * ch].reshape(h, w, ch)
                img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR) if ch == 1 else img[:, :, :3][:, :, ::-1].copy()
            if img is not None:
                out.append(img)
        except Exception:  # noqa: BLE001 - imagen con formato no soportado
            continue
    return out


def _identify(db: Session, text: str, people: list[Person]) -> Person | None:
    for a, b in CEDULA.findall(text):
        p = db.get(Person, f"{a}-{b}")
        if p is not None:
            return p
    t = f" {_norm(text)} "
    matches = []
    for p in people:
        tokens = [w for w in _norm(f"{p.apellidos} {p.nombres}").split() if len(w) > 1]
        if len(tokens) >= 2 and all(f" {w} " in t for w in tokens):
            matches.append((len(tokens), p))
    if not matches:
        return None
    matches.sort(key=lambda m: -m[0])
    # Ambiguo (dos estudiantes con el mismo numero de palabras coincidentes): no se asigna.
    if len(matches) > 1 and matches[0][0] == matches[1][0]:
        return None
    return matches[0][1]


def _best_face_image(images: list[np.ndarray]):
    import cv2

    best = None
    for img in images:
        h, w = img.shape[:2]
        if max(h, w) < 480:  # foto carnet pequena -> ampliar para el detector
            k = 480 / max(h, w)
            img = cv2.resize(img, (int(w * k), int(h * k)), interpolation=cv2.INTER_CUBIC)
        faces = engine.detect(img)
        if len(faces) != 1:
            continue
        size = faces[0].box[2] * faces[0].box[3]
        if best is None or size > best[0]:
            best = (size, img)
    return best[1] if best else None


def enroll_from_pdf(db: Session, data: bytes, by: str, allowed_cedula: str | None, fallback_cedula: str | None) -> list[PdfEnrollResult]:
    """`allowed_cedula`: si se indica (estudiante), solo puede registrarse esa persona."""
    import cv2

    pages = _read_pdf(data)
    if not pages:
        raise ValueError("El PDF no tiene páginas")
    people = list(db.scalars(select(Person).where(Person.role == "ESTUDIANTE")))
    results: list[PdfEnrollResult] = []
    for i, pg in enumerate(pages, start=1):
        if not pg.images and not pg.text.strip():
            continue
        person = _identify(db, pg.text, people)
        if person is None and fallback_cedula and not pg.text.strip():
            person = db.get(Person, fallback_cedula)
        if person is None:
            if pg.images:
                results.append(PdfEnrollResult(i, None, None, "NO_IDENTIFICADO",
                                               "No se encontró una cédula o nombre de un estudiante sincronizado"))
            continue
        name = f"{person.nombres} {person.apellidos}".strip()
        if allowed_cedula and person.cedula != allowed_cedula:
            results.append(PdfEnrollResult(i, person.cedula, name, "NO_AUTORIZADO",
                                           "La matrícula pertenece a otra persona"))
            continue
        img = _best_face_image(pg.images)
        if img is None:
            results.append(PdfEnrollResult(i, person.cedula, name, "SIN_ROSTRO",
                                           "No se encontró una foto con un solo rostro en esta página"))
            continue
        ok, buf = cv2.imencode(".jpg", img)
        added, problems = att.enroll(db, person.cedula, [buf.tobytes()], by=by)
        results.append(PdfEnrollResult(i, person.cedula, name, "REGISTRADO" if added else "SIN_ROSTRO",
                                       "; ".join(problems)))
    if not results:
        raise ValueError("El PDF no contiene fotos ni datos de estudiantes")
    return results
