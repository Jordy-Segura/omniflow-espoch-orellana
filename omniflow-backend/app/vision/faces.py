"""Reconocimiento facial para la asistencia automatica.

Pipeline (todo en el servidor; el telefono-camara solo envia el fotograma):
  1. Deteccion de rostros: YuNet (CNN ligera, OpenCV Zoo, Apache-2.0).
  2. Alineacion con 5 puntos (ojos, nariz, comisuras) y recorte 112x112.
  3. Embedding: SFace (MobileFaceNet entrenado con perdida tipo ArcFace) -> vector de 128 dimensiones.
  4. Clasificacion de conjunto abierto: 1-NN por similitud coseno contra las plantillas de los estudiantes
     INSCRITOS en la clase (no de toda la universidad), con umbral y margen aprendidos en el notebook
     de ML (omniflow-ml/) sobre el dataset LFW -> `app/ml_models/face_policy.json`.

Privacidad: se guardan solo los vectores, nunca las fotos (ver models.FaceTemplate).
"""
from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MODELS_DIR = Path(__file__).resolve().parent.parent / "ml_models"
YUNET = MODELS_DIR / "face_detection_yunet_2023mar.onnx"
SFACE = MODELS_DIR / "face_recognition_sface_2021dec.onnx"
POLICY = MODELS_DIR / "face_policy.json"


@dataclass(frozen=True)
class FacePolicy:
    threshold: float = 0.363      # coseno minimo para aceptar identidad (OpenCV recomienda 0.363 para SFace)
    margin: float = 0.04          # ventaja minima sobre el segundo candidato (evita confundir parecidos)
    min_det_score: float = 0.80   # confianza minima del detector
    min_face_px: int = 24         # rostros mas pequenos se ignoran (muy lejos de la camara)
    source: str = "default"

    @staticmethod
    def load(path: Path = POLICY) -> FacePolicy:
        if not path.exists():
            return FacePolicy()
        d = json.loads(path.read_text(encoding="utf-8"))
        return FacePolicy(
            threshold=float(d.get("threshold", 0.363)), margin=float(d.get("margin", 0.04)),
            min_det_score=float(d.get("min_det_score", 0.80)), min_face_px=int(d.get("min_face_px", 24)),
            source=str(d.get("source", path.name)),
        )


@dataclass(frozen=True)
class DetectedFace:
    box: tuple[int, int, int, int]  # x, y, w, h en pixeles
    score: float
    embedding: np.ndarray           # (128,) normalizado L2


@dataclass(frozen=True)
class Match:
    face_index: int
    cedula: str
    similarity: float


def l2n(v: np.ndarray) -> np.ndarray:
    v = np.asarray(v, dtype=np.float32).reshape(-1)
    n = float(np.linalg.norm(v))
    return v / n if n > 0 else v


def match_faces(faces: list[np.ndarray], gallery: dict[str, np.ndarray], policy: FacePolicy) -> list[Match]:
    """Asigna rostros a estudiantes (conjunto abierto).

    - Puntaje estudiante = max coseno contra sus plantillas (1-NN).
    - Se acepta si puntaje >= umbral y supera al segundo mejor estudiante por `margin`.
    - Asignacion 1 a 1 voraz por mayor similitud: un estudiante no puede ser dos rostros.
    """
    if not faces or not gallery:
        return []
    ids = list(gallery)
    F = np.stack([l2n(f) for f in faces])                       # (n_faces, 128)
    scores = np.zeros((len(faces), len(ids)), dtype=np.float32)
    for j, ced in enumerate(ids):
        T = np.atleast_2d(gallery[ced]).astype(np.float32)        # (n_templates, 128) ya normalizadas
        scores[:, j] = (F @ T.T).max(axis=1)

    candidates = []
    for i in range(len(faces)):
        order = np.argsort(-scores[i])
        best = order[0]
        second = scores[i, order[1]] if len(order) > 1 else -1.0
        s = float(scores[i, best])
        if s >= policy.threshold and s - float(second) >= policy.margin:
            candidates.append((s, i, ids[best]))
    out, used_faces, used_ids = [], set(), set()
    for s, i, ced in sorted(candidates, reverse=True):
        if i in used_faces or ced in used_ids:
            continue
        used_faces.add(i)
        used_ids.add(ced)
        out.append(Match(i, ced, s))
    return out


class FaceEngine:
    """Carga diferida de YuNet + SFace (OpenCV DNN). Un candado: las redes no son thread-safe."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._det = None
        self._rec = None
        self.policy = FacePolicy.load()

    @staticmethod
    def available() -> bool:
        try:
            import cv2  # noqa: F401
        except ImportError:
            return False
        return YUNET.exists() and SFACE.exists()

    def _load(self):
        import cv2

        if self._det is None:
            self._det = cv2.FaceDetectorYN.create(str(YUNET), "", (320, 320), self.policy.min_det_score, 0.3, 200)
            self._rec = cv2.FaceRecognizerSF.create(str(SFACE), "")
        return cv2

    def decode(self, data: bytes):
        import cv2

        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError("La imagen no es un JPEG/PNG válido")
        h, w = img.shape[:2]
        scale = 1280 / max(h, w)
        if scale < 1:
            img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
        return img

    def detect(self, img) -> list[DetectedFace]:
        with self._lock:
            self._load()
            h, w = img.shape[:2]
            self._det.setInputSize((w, h))
            _, faces = self._det.detect(img)
            out = []
            for f in faces if faces is not None else []:
                x, y, fw, fh = (int(v) for v in f[:4])
                score = float(f[-1])
                if score < self.policy.min_det_score or min(fw, fh) < self.policy.min_face_px:
                    continue
                emb = self._rec.feature(self._rec.alignCrop(img, f))
                out.append(DetectedFace((x, y, fw, fh), score, l2n(emb)))
            return out


engine = FaceEngine()
