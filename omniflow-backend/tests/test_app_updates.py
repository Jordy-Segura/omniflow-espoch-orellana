import hashlib

APK = b"PK\x03\x04" + b"omniflow-apk-de-prueba" * 100


def publish(client, code, key="clave-release", body=APK):
    return client.put(f"/api/v1/app/releases?versionCode={code}&versionName=0.1.{code}&notes=Mejoras",
                      content=body, headers={"X-Release-Key": key, "Content-Type": "application/vnd.android.package-archive"})


def test_no_release_yet(client):
    assert client.get("/api/v1/app/latest").status_code == 404


def test_publish_requires_configured_key(client, settings):
    settings.release_upload_key = ""
    assert publish(client, 10).status_code == 403
    settings.release_upload_key = "clave-release"
    assert publish(client, 10, key="otra").status_code == 401


def test_publish_check_and_download(client, settings):
    settings.release_upload_key = "clave-release"
    r = publish(client, 29800000)
    assert r.status_code == 201, r.text
    latest = client.get("/api/v1/app/latest").json()
    assert latest["versionCode"] == 29800000 and latest["versionName"] == "0.1.29800000"
    assert latest["sha256"] == hashlib.sha256(APK).hexdigest() and latest["size"] == len(APK)
    apk = client.get(latest["downloadUrl"])
    assert apk.status_code == 200 and apk.content == APK
    assert apk.headers["content-type"] == "application/vnd.android.package-archive"

    assert publish(client, 29800000).status_code == 409  # versionCode debe crecer
    assert publish(client, 29800001, body=b"no es apk").status_code == 400
    assert client.get("/api/v1/app/download/..%2F..%2Fsecreto.apk").status_code == 404
    assert client.get("/api/v1/app/download/latest.json").status_code == 404
