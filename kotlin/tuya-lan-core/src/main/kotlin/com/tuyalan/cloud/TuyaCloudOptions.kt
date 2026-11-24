package com.tuyalan.cloud

/** Tuya data centre a project is hosted in. The endpoint must match the account region. */
enum class TuyaCloudRegion(val baseUrl: String) {
    CHINA("https://openapi.tuyacn.com"),
    WESTERN_AMERICA("https://openapi.tuyaus.com"),
    EASTERN_AMERICA("https://openapi-ueaz.tuyaus.com"),
    CENTRAL_EUROPE("https://openapi.tuyaeu.com"),
    WESTERN_EUROPE("https://openapi-weaz.tuyaeu.com"),
    INDIA("https://openapi.tuyain.com"),
    SINGAPORE("https://openapi-sg.iotbing.com"),
}

/** Credentials and endpoint for the Tuya Cloud API. */
data class TuyaCloudOptions(
    /** Access ID of the cloud project's authorisation key pair. */
    val clientId: String,
    /** Access secret of the cloud project's authorisation key pair. */
    val secret: String,
    /** Data centre hosting the project. */
    val region: TuyaCloudRegion = TuyaCloudRegion.INDIA,
    /** Overrides [region] when set, e.g. for a private endpoint. */
    val baseUrlOverride: String? = null,
    /** Value for the `lang` header. "en" outside mainland China. */
    val language: String = "en",
) {
    /** The base URL that will be used. */
    val baseUrl: String get() = baseUrlOverride?.trimEnd('/') ?: region.baseUrl

    init {
        require(clientId.isNotBlank()) { "clientId is required" }
        require(secret.isNotBlank()) { "secret is required" }
    }
}
