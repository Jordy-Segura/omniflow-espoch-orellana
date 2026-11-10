from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.auth import MANAGERS, STAFF
from app.database import get_db
from app.models import Camera
from app.repositories import CameraRepository, LabRepository, audit
from app.schemas import CameraIn, CameraOut, CameraStatusOut, UserOut
from app.vision import vision_manager

router = APIRouter(prefix="/api/v1/cameras", tags=["cámaras"])


def _out(c: Camera) -> CameraOut:
    return CameraOut(id=c.id, lab_code=c.lab.code, name=c.name, url=c.url, enabled=c.enabled,
                     counting_line=c.counting_line, line_position=c.line_position, down_is_entry=c.down_is_entry)


def _cam_or_404(db: Session, cam_id: int) -> Camera:
    c = CameraRepository(db).get(cam_id)
    if c is None:
        raise HTTPException(404, "Cámara no encontrada")
    return c


@router.get("", response_model=list[CameraOut])
def list_cameras(db: Session = Depends(get_db), _: UserOut = Depends(STAFF)) -> list[CameraOut]:
    return [_out(c) for c in CameraRepository(db).list()]


@router.post("", response_model=CameraOut, status_code=201)
def create_camera(body: CameraIn, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> CameraOut:
    lab = LabRepository(db).by_code(body.lab_code)
    if lab is None:
        raise HTTPException(404, f"Laboratorio {body.lab_code} no existe")
    cam = CameraRepository(db).add(Camera(lab_id=lab.id, **body.model_dump(exclude={"lab_code"})))
    audit(db, user.username, "CAMARA_CREADA", f"{body.lab_code} {body.name}")
    db.commit()
    db.refresh(cam)
    if cam.enabled and vision_manager.available():
        vision_manager.start(cam)
    return _out(cam)


@router.delete("/{cam_id}", status_code=204)
def delete_camera(cam_id: int, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> None:
    cam = _cam_or_404(db, cam_id)
    vision_manager.stop(cam_id)
    CameraRepository(db).delete(cam)
    audit(db, user.username, "CAMARA_ELIMINADA", str(cam_id))
    db.commit()


@router.post("/{cam_id}/start", response_model=CameraStatusOut)
def start_camera(cam_id: int, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> CameraStatusOut:
    cam = _cam_or_404(db, cam_id)
    if not vision_manager.available():
        raise HTTPException(503, "Módulo de visión no instalado (pip install -r requirements-vision.txt)")
    cam.enabled = True
    audit(db, user.username, "CAMARA_INICIADA", str(cam_id))
    db.commit()
    vision_manager.start(cam)
    return _status(cam_id)


@router.post("/{cam_id}/stop", response_model=CameraStatusOut)
def stop_camera(cam_id: int, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> CameraStatusOut:
    cam = _cam_or_404(db, cam_id)
    cam.enabled = False
    audit(db, user.username, "CAMARA_DETENIDA", str(cam_id))
    db.commit()
    vision_manager.stop(cam_id)
    return _status(cam_id)


def _status(cam_id: int) -> CameraStatusOut:
    st = vision_manager.status(cam_id)
    return CameraStatusOut(id=cam_id, running=st.running, connected=st.connected, people=st.people,
                           entries=st.entries, exits=st.exits, fps=st.fps, inference_ms=st.inference_ms,
                           last_error=st.last_error, model=st.model)


@router.get("/{cam_id}/status", response_model=CameraStatusOut)
def camera_status(cam_id: int, db: Session = Depends(get_db), _: UserOut = Depends(STAFF)) -> CameraStatusOut:
    _cam_or_404(db, cam_id)
    return _status(cam_id)


@router.get("/{cam_id}/snapshot.jpg", responses={200: {"content": {"image/jpeg": {}}}})
def snapshot(cam_id: int, db: Session = Depends(get_db), _: UserOut = Depends(STAFF)) -> Response:
    """Ultimo frame procesado con las cajas y los IDs de ByteTrack dibujados."""
    _cam_or_404(db, cam_id)
    jpg = vision_manager.snapshot(cam_id)
    if jpg is None:
        raise HTTPException(404, "Aún no hay frames procesados para esta cámara")
    return Response(jpg, media_type="image/jpeg", headers={"Cache-Control": "no-store"})
