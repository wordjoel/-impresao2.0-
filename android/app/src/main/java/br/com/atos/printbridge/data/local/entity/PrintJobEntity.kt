package br.com.atos.printbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "print_jobs")
data class PrintJobEntity(
    @PrimaryKey val id: String,
    val printerId: String,
    val type: String,
    val payload: String,
    val documentName: String,
    val copies: Int,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val errorMessage: String?,
    val externalDocumentId: String?,
    val originalJobId: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val sentAt: Long?,
    val updatedAt: Long,
    val validationWarnings: String?,
    // v7 — documento multipágina: N jobs (um por página) com o mesmo groupId, impressos em ordem.
    val groupId: String? = null,
    val pageIndex: Int? = null,
    val pageCount: Int? = null,
    // v8 — retrato da impressora no momento do envio, para o Histórico mostrar IP/protocolo reais
    // mesmo se a impressora for editada ou removida depois.
    val printerName: String? = null,
    val printerAddress: String? = null,
    val printerProtocol: String? = null,
    // v9 — origem do job (PROMPT MASTER §24), para vários coletores na mesma loja. Tudo NULL em
    // jobs antigos; collectorId/operatorName ficam nulos até a Central de Tokens e o login de operador.
    val storeId: String? = null,
    val collectorId: String? = null,
    val deviceId: String? = null,
    val sourceApp: String? = null,
    val operatorName: String? = null,
)
