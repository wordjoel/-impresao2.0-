package br.com.atos.printbridge.application.coletor

import br.com.atos.printbridge.domain.coletor.ColetorLink
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheck
import br.com.atos.printbridge.domain.coletor.StrictJson
import java.util.Base64

/** Fixture real do print-server (ver ColetorCrossLanguageTest) + fakes de rede, armazenamento e relógio. */
object ColetorFixture {
    @Suppress("UNCHECKED_CAST")
    val data: Map<String, Any?> by lazy {
        StrictJson.parse(ColetorFixture::class.java.getResource("/coletor/fixture.json")!!.readText(Charsets.UTF_8)) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private val envelope get() = data["envelope"] as Map<String, Any?>
    val templateId = "fixture-gondola"
    val sha get() = envelope["packageSha256"] as String
    val version = 7

    /** O envelope como o PC devolve (JSON). */
    val envelopeJson: String by lazy {
        @Suppress("UNCHECKED_CAST")
        val sig = envelope["signature"] as Map<String, Any?>
        val pj = envelope["packageJson"] as String
        """{"packageJson":${quote(pj)},"packageSha256":"$sha","signature":{"alg":"ES256","kid":"${sig["kid"]}","value":"${sig["value"]}"}}"""
    }

    fun link(kid: String = data["kid"] as String) = ColetorLink(
        deviceId = "dev-1",
        token = "t".repeat(43),
        host = "10.10.40.10",
        port = 7172,
        kid = kid,
        publicKeySpki = Base64.getDecoder().decode(data["publicKeySpkiB64"] as String),
    )

    /** Caso de montagem da fixture: (valores, cópias, SHA-256 esperado) do alvo [target]. */
    @Suppress("UNCHECKED_CAST")
    fun case(target: Int, copies: Int): Triple<Map<String, String>, Int, String> {
        val c = (data["cases"] as List<Map<String, Any?>>).first { (it["target"] as Long).toInt() == target && (it["copies"] as Long).toInt() == copies }
        return Triple(c["values"] as Map<String, String>, copies, c["result"] as String)
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

class FakeColetorRemote(
    var versionAnswer: VersionCheck = VersionCheck.Answered(ColetorFixture.version, ColetorFixture.sha),
    var envelopeAnswer: ColetorRemote.Envelope = ColetorRemote.Envelope.Ok(ColetorFixture.envelopeJson),
) : ColetorRemote {
    var versionCalls = 0
    var envelopeCalls = 0
    override fun version(link: ColetorLink, templateId: String): VersionCheck {
        versionCalls++
        return versionAnswer
    }

    override fun envelope(link: ColetorLink, templateId: String): ColetorRemote.Envelope {
        envelopeCalls++
        return envelopeAnswer
    }

    var reportAnswer: ColetorRemote.ReportResult = ColetorRemote.ReportResult.Accepted
    val reports = mutableListOf<String>()
    override fun report(link: ColetorLink, json: String): ColetorRemote.ReportResult {
        reports += json
        return reportAnswer
    }
}

class FakeColetorReportStore : ColetorReportStore {
    val items = linkedMapOf<String, br.com.atos.printbridge.domain.coletor.ColetorPrintReport>()
    override fun all() = items.values.toList()
    override fun save(report: br.com.atos.printbridge.domain.coletor.ColetorPrintReport) { items[report.reportId] = report }
    override fun delete(reportId: String) { items.remove(reportId) }
}

class FakeColetorStore(var linkValue: ColetorLink? = ColetorFixture.link()) : ColetorLocalStore {
    val envelopes = mutableMapOf<String, String>()
    val confirmations = mutableMapOf<String, ColetorPrintPolicy.Confirmation>()
    override fun link() = linkValue
    override fun envelope(templateId: String) = envelopes[templateId]
    override fun saveEnvelope(templateId: String, json: String) { envelopes[templateId] = json }
    override fun deleteEnvelope(templateId: String) {
        envelopes.remove(templateId)
        confirmations.remove(templateId)
    }
    override fun confirmation(templateId: String) = confirmations[templateId]
    override fun saveConfirmation(templateId: String, confirmation: ColetorPrintPolicy.Confirmation) { confirmations[templateId] = confirmation }
}

class FakeDeviceClock(var elapsed: Long = 1_000_000L, var boot: String = "3") : DeviceClock {
    override fun elapsedRealtimeMs() = elapsed
    override fun bootId() = boot
}
