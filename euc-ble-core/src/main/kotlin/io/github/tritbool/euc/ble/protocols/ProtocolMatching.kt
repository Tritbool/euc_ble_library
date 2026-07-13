package io.github.tritbool.euc.ble.protocols

internal object ProtocolMatching {
    private val genericNames = setOf(
        "unknown",
        "ble",
        "bluetooth",
        "device",
        "wheel",
        "unicycle",
        "euc",
        "euc wheel"
    )

    fun normalizeName(value: String): String {
        return value.trim().lowercase()
    }

    fun isGenericDeviceName(name: String): Boolean {
        val normalized = normalizeName(name)
        if (normalized.isBlank()) return true
        if (normalized.length < 3) return true
        return normalized in genericNames
    }

    fun hasStrongModelNameMatch(name: String, supportedModels: List<String>): Boolean {
        if (isGenericDeviceName(name)) return false
        val normalizedName = normalizeName(name)
        return hasExactModelTokenMatch(normalizedName, supportedModels) ||
            hasModelSubstringMatch(normalizedName, supportedModels)
    }

    private fun hasExactModelTokenMatch(normalizedName: String, supportedModels: List<String>): Boolean {
        val tokens = normalizedName.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        if (tokens.isEmpty()) return false
        return supportedModels.any { model ->
            val normalizedModel = normalizeName(model)
            tokens.contains(normalizedModel)
        }
    }

    private fun hasModelSubstringMatch(normalizedName: String, supportedModels: List<String>): Boolean {
        return supportedModels.any { model ->
            val normalizedModel = normalizeName(model)
            normalizedModel.length >= 4 && normalizedName.contains(normalizedModel)
        }
    }
}
