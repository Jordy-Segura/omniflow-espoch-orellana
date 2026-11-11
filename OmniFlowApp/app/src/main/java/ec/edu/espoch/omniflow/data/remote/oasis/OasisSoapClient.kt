package ec.edu.espoch.omniflow.data.remote.oasis

import android.util.Xml
import ec.edu.espoch.omniflow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

/** Nodo XML simplificado para leer las respuestas SOAP. */
data class XmlNode(val name: String, val text: String, val children: List<XmlNode>) {
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun value(name: String): String = child(name)?.text.orEmpty()
    fun find(name: String): XmlNode? {
        if (this.name == name) return this
        for (c in children) c.find(name)?.let { return it }
        return null
    }
}

class OasisException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Cliente SOAP 1.1 para los servicios de interoperabilidad de OASIS (ESPOCH):
 *  - Seguridad.asmx   -> AutenticarUsuarioCarrera, GetUsuarioFacultad, GetRolUsuarioCarrera
 *  - InfoGeneral.asmx -> GetPeriodoActual, ...
 *  - InfoCarrera.asmx -> GetHorarioDocente, GetHorarioEstudiante, GetDatosDocente, ...
 *
 * Se envia la cabecera SOAP <credentials>; el usuario/clave de servicio es OPCIONAL: OASIS responde
 * sin ellos para consultar horarios, docentes y matriculas. Si la DTIC entrega uno, va en local.properties
 * (OASIS_WS_USER / OASIS_WS_PASSWORD).
 *
 * Nota de arquitectura: en produccion estas llamadas las hara el backend OmniFlow (FastAPI);
 * este cliente permite trabajar desde la app mientras el backend se construye.
 */
@Singleton
class OasisSoapClient @Inject constructor(private val http: OkHttpClient) {

    /** true si hay credenciales de servicio (opcionales). */
    val hasServiceCredentials: Boolean
        get() = BuildConfig.OASIS_WS_USER.isNotBlank() && BuildConfig.OASIS_WS_PASSWORD.isNotBlank()

    suspend fun call(service: String, method: String, params: List<Pair<String, String>>): XmlNode =
        withContext(Dispatchers.IO) {
            val body = envelope(method, params)
            val request = Request.Builder()
                .url(BuildConfig.OASIS_BASE_URL.trimEnd('/') + "/$service.asmx")
                .header("SOAPAction", "\"$NS$method\"")
                .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()
            val xml = try {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body.string()
                    if (!resp.isSuccessful) {
                        val fault = runCatching { parse(text).find("faultstring")?.text }.getOrNull()
                            ?.substringAfterLast("--->")?.trim()
                        throw OasisException(fault ?: "OASIS respondió HTTP ${resp.code}")
                    }
                    text
                }
            } catch (e: OasisException) {
                throw e
            } catch (e: IOException) {
                throw OasisException("No se pudo conectar con OASIS: ${e.message}", e)
            }
            val root = parse(xml)
            root.find("${method}Result") ?: XmlNode("${method}Result", "", emptyList())
        }

    private fun envelope(method: String, params: List<Pair<String, String>>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="$NS">""")
        append("<soap:Header><tns:credentials>")
        append("<tns:username>").append(esc(BuildConfig.OASIS_WS_USER)).append("</tns:username>")
        append("<tns:password>").append(esc(BuildConfig.OASIS_WS_PASSWORD)).append("</tns:password>")
        append("</tns:credentials></soap:Header>")
        append("<soap:Body><tns:$method>")
        params.forEach { (k, v) -> append("<tns:$k>").append(esc(v)).append("</tns:$k>") }
        append("</tns:$method></soap:Body></soap:Envelope>")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun parse(xml: String): XmlNode {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        data class Builder(val name: String, val text: StringBuilder = StringBuilder(), val children: MutableList<XmlNode> = mutableListOf())
        val stack = ArrayDeque<Builder>().apply { addLast(Builder("#root")) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> stack.addLast(Builder(parser.name))
                XmlPullParser.TEXT -> stack.last().text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val b = stack.removeLast()
                    stack.last().children += XmlNode(b.name, b.text.toString().trim(), b.children)
                }
            }
            event = parser.next()
        }
        val root = stack.first()
        return XmlNode(root.name, "", root.children)
    }

    // ---------------------------------------------------------------- Seguridad

    data class RolCarrera(val codigoCarrera: String, val nombreRol: String)
    data class Persona(val cedula: String, val apellidos: String, val nombres: String, val email: String)

    suspend fun autenticarUsuarioCarrera(login: String, password: String): List<RolCarrera> =
        call("Seguridad", "AutenticarUsuarioCarrera", listOf("login" to login, "password" to password))
            .children.filter { it.name == "RolCarrera" }
            .map { RolCarrera(it.value("CodigoCarrera"), it.value("NombreRol")) }

    suspend fun getUsuarioFacultad(login: String, password: String): Persona? =
        call("Seguridad", "GetUsuarioFacultad", listOf("login" to login, "password" to password))
            .takeIf { it.children.isNotEmpty() }
            ?.let { Persona(it.value("Cedula"), it.value("Apellidos"), it.value("Nombres"), it.value("Email")) }

    // ---------------------------------------------------------------- InfoGeneral

    data class Periodo(val codigo: String, val descripcion: String, val inicio: String, val fin: String)

    suspend fun getPeriodoActual(): Periodo? =
        call("InfoGeneral", "GetPeriodoActual", emptyList())
            .takeIf { it.children.isNotEmpty() }
            ?.let { Periodo(it.value("Codigo"), it.value("Descripcion"), it.value("FechaInicio"), it.value("FechaFin")) }

    // ---------------------------------------------------------------- InfoCarrera

    /** Fila del horario semanal: una franja horaria con el contenido de cada dia (texto libre de OASIS). */
    data class FilaHorario(val inicioFin: String, val dias: List<String>)

    suspend fun getHorarioDocente(codCarrera: String, cedula: String): List<FilaHorario> =
        horario("GetHorarioDocente", "HorarioDocente", codCarrera, cedula)

    suspend fun getHorarioEstudiante(codCarrera: String, cedula: String): List<FilaHorario> =
        horario("GetHorarioEstudiante", "HorarioEstudiante", codCarrera, cedula)

    private suspend fun horario(method: String, item: String, codCarrera: String, cedula: String): List<FilaHorario> =
        call("InfoCarrera", method, listOf("CodCarrera" to codCarrera, "strCedula" to cedula))
            .children.filter { it.name == item }
            .map { n ->
                FilaHorario(
                    inicioFin = n.value("strInicioFinHora"),
                    dias = listOf("strLunes", "strMartes", "strMiercoles", "strJueves", "strViernes", "strSabado", "strDomingo")
                        .map { n.value(it) },
                )
            }

    companion object {
        const val NS = "http://academico.espoch.edu.ec/"
    }
}
