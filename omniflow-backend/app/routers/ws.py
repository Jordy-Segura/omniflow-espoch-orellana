from fastapi import APIRouter, HTTPException, Query, WebSocket, WebSocketDisconnect

from app.auth import decode_token
from app.websocket import manager

router = APIRouter(tags=["tiempo real"])


@router.websocket("/ws/live")
async def live(ws: WebSocket, token: str = Query(...)) -> None:
    """Eventos en tiempo real: `{"type": "occupancy", ...}` y `{"type": "alert", ...}`.
    El JWT va en la query (`/ws/live?token=...`) porque los navegadores no permiten cabeceras en WebSocket."""
    try:
        user = decode_token(token)
    except HTTPException:
        await ws.close(code=4401)
        return
    await manager.connect(ws)
    try:
        await ws.send_json({"type": "hello", "user": user.username})
        while True:
            await ws.receive_text()  # mantener viva la conexion (ping del cliente)
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(ws)
