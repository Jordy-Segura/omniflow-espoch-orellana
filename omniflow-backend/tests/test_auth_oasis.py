import httpx
import pytest

from app.auth import LoginError, login_oasis
from app.integrations.oasis import OasisClient, parse_time_range
from conftest import token_for

ROLES_XML = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
<AutenticarUsuarioCarreraResponse xmlns="http://academico.espoch.edu.ec/">
<AutenticarUsuarioCarreraResult><RolCarrera><CodigoCarrera>EIS</CodigoCarrera><NombreRol>DOCENTE</NombreRol></RolCarrera>
</AutenticarUsuarioCarreraResult></AutenticarUsuarioCarreraResponse></soap:Body></soap:Envelope>"""

PERSONA_XML = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
<GetUsuarioFacultadResponse xmlns="http://academico.espoch.edu.ec/"><GetUsuarioFacultadResult>
<Cedula>0600000000</Cedula><Apellidos>García</Apellidos><Nombres>Carlos</Nombres><Email>cgarcia@espoch.edu.ec</Email>
</GetUsuarioFacultadResult></GetUsuarioFacultadResponse></soap:Body></soap:Envelope>"""

EMPTY_XML = ROLES_XML.replace(
    "<RolCarrera><CodigoCarrera>EIS</CodigoCarrera><NombreRol>DOCENTE</NombreRol></RolCarrera>", "")

FAULT_XML = """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>
<faultcode>soap:Client</faultcode><faultstring>Credenciales de servicio invalidas</faultstring></soap:Fault></soap:Body></soap:Envelope>"""


def oasis_client(settings, handler):
    settings.oasis_ws_user = "svc"
    settings.oasis_ws_password = "svc-pass"
    return OasisClient(transport=httpx.MockTransport(handler))


def test_oasis_login_sends_service_credentials_and_maps_role(settings):
    seen = []

    def handler(req: httpx.Request) -> httpx.Response:
        body = req.content.decode()
        seen.append((req.url.path, req.headers["SOAPAction"], body))
        xml = ROLES_XML if "AutenticarUsuarioCarrera" in body else PERSONA_XML
        return httpx.Response(200, text=xml)

    user = login_oasis("cgarcia@espoch.edu.ec", "secreto", oasis_client(settings, handler))
    assert user.role == "DOCENTE"
    assert user.full_name == "Carlos García"
    assert user.cedula == "0600000000"
    assert user.career_code == "EIS"
    path, action, body = seen[0]
    assert path.endswith("/Seguridad.asmx")
    assert action == '"http://academico.espoch.edu.ec/AutenticarUsuarioCarrera"'
    assert "<tns:username>svc</tns:username>" in body and "<tns:login>cgarcia</tns:login>" in body


def test_oasis_wrong_password_is_401(settings):
    client = oasis_client(settings, lambda req: httpx.Response(200, text=EMPTY_XML))
    with pytest.raises(LoginError) as e:
        login_oasis("x", "y", client)
    assert e.value.code == 401


def test_oasis_soap_fault_is_502(settings):
    client = oasis_client(settings, lambda req: httpx.Response(500, text=FAULT_XML))
    with pytest.raises(LoginError) as e:
        login_oasis("x", "y", client)
    assert e.value.code == 502
    assert "Credenciales de servicio" in str(e.value)


def test_oasis_admin_override(settings):
    settings.admin_users = "cgarcia"
    client = oasis_client(settings, lambda req: httpx.Response(
        200, text=ROLES_XML if "Autenticar" in req.content.decode() else PERSONA_XML))
    assert login_oasis("cgarcia", "p", client).role == "ADMINISTRADOR"


NULLREF_XML = FAULT_XML.replace("Credenciales de servicio invalidas",
                                 "El servidor no puede procesar la solicitud. ---&gt; Referencia a objeto no establecida como instancia de un objeto.")


def test_oasis_works_without_service_credentials_and_unknown_user_is_401(settings):
    settings.oasis_ws_user = ""
    settings.oasis_ws_password = ""
    sent = []

    def handler(req):
        sent.append(req.content.decode())
        return httpx.Response(500, text=NULLREF_XML)

    with pytest.raises(LoginError) as e:
        login_oasis("no.existe", "x", OasisClient(transport=httpx.MockTransport(handler)))
    assert e.value.code == 401
    assert "<tns:username></tns:username>" in sent[0]


def test_demo_login_and_me(client):
    h = token_for(client, "DOCENTE", "Ana Pérez")
    me = client.get("/api/v1/auth/me", headers=h).json()
    assert me["role"] == "DOCENTE" and me["source"] == "DEMO" and me["fullName"] == "Ana Pérez"


def test_demo_login_can_be_disabled(client, settings):
    settings.demo_login_enabled = False
    r = client.post("/api/v1/auth/login", json={"username": "x", "demoRole": "ADMINISTRADOR"})
    assert r.status_code == 403


def test_protected_routes_require_token_and_role(client):
    assert client.get("/api/v1/alerts").status_code == 401
    assert client.get("/api/v1/alerts", headers={"Authorization": "Bearer basura"}).status_code == 401
    student = token_for(client, "ESTUDIANTE")
    assert client.get("/api/v1/alerts", headers=student).status_code == 200
    assert client.get("/api/v1/reports/weekly", headers=student).status_code == 403
    assert client.post("/api/v1/labs", headers=student,
                       json={"code": "LAB-X", "name": "Lab X", "capacity": 10}).status_code == 403


def test_parse_oasis_time_range():
    assert parse_time_range("07:00 - 08:00") == (420, 480)
    assert parse_time_range("14h00-16h00") == (840, 960)
    assert parse_time_range("sin hora") is None
