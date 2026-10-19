package br.com.atos.printbridge.domain.model

/**
 * Estado de um Device — CARREFOUR-PRINT-DEVICE-IDENTITY.md §2.1. REGISTERED é o estado inicial
 * (registro de negócio criado, ainda sem ativação); ACTIVE após DeviceActivation bem-sucedida;
 * REVOKED é terminal (reativar é sempre um Device novo — regra de TENANTS.md §1).
 */
enum class DeviceStatus {
    REGISTERED,
    ACTIVE,
    REVOKED,
}

/**
 * Device — o registro de negócio (o "slot" licenciado) no modelo por loja (Fase 3 do fluxo
 * Carrefour). Pertence a uma Store (storeId, nullable até o device ser vinculado a uma loja) e
 * agrega a identidade criptográfica produzida por `DeviceIdentityProvider` (derivada do Keystore).
 * `deviceId` é o identificador derivado da chave pública — nunca IMEI/serial (DEVICE-IDENTITY.md §1).
 */
data class Device(
    val deviceId: String,
    val storeId: String?,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
    val status: DeviceStatus,
    val lastSeenAt: Long?,
    val activatedAt: Long?,
)