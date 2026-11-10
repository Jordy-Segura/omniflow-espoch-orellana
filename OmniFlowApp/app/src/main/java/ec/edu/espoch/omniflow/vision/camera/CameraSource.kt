package ec.edu.espoch.omniflow.vision.camera

/**
 * Fuente de video para un laboratorio.
 *
 * Mientras no se adquieran camaras IP reales, el telefono puede usar:
 *  - su propia camara trasera/frontal (el telefono queda fijo apuntando al laboratorio), o
 *  - la camara de OTRO telefono que transmite por Wi-Fi (IP Webcam / DroidCam) -> [IP_STREAM].
 * Cuando llegue la camara IP real basta con cambiar la URL en Configuracion.
 */
enum class CameraSource(val label: String, val description: String) {
    PHONE_BACK("Cámara trasera del teléfono", "Coloca el teléfono fijo apuntando al laboratorio"),
    PHONE_FRONT("Cámara frontal del teléfono", "Útil para pruebas rápidas frente a la pantalla"),
    IP_STREAM("Otro teléfono / cámara IP (Wi-Fi)", "Stream MJPEG o foto JPG por HTTP en la misma red"),
}
