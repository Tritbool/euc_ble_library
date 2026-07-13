package io.github.tritbool.euc.ble.protocols

internal object ProtocolMatching {
    private val tokenSplitRegex = Regex("[^a-z0-9]+")
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
        val normalizedModels = supportedModels.map(::normalizeName)
        return hasExactModelTokenMatch(normalizedName, normalizedModels) ||
            hasModelSubstringMatch(normalizedName, normalizedModels)
    }

    fun tokenizeName(name: String): Set<String> {
        val normalized = normalizeName(name)
        return normalized.split(tokenSplitRegex)
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun hasExactModelTokenMatch(normalizedName: String, normalizedModels: List<String>): Boolean {
        val tokens = tokenizeName(normalizedName)
        if (tokens.isEmpty()) return false
        return normalizedModels.any { model -> tokens.contains(model) }
    }

    private fun hasModelSubstringMatch(normalizedName: String, normalizedModels: List<String>): Boolean {
        return normalizedModels.any { model ->
            model.length >= 4 && normalizedName.contains(model)
        }
    }
}
