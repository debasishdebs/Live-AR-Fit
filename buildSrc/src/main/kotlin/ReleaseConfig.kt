/** Release signing inputs (spec §3): keystore.properties or env vars, env winning; blank counts as missing. */
data class SigningInput(val storeFile: String, val keyAlias: String, val storePassword: String, val keyPassword: String)

sealed interface SigningResolution {
    data class Release(val input: SigningInput) : SigningResolution
    /** Nothing configured: a local release build is debug-signed and named `*-unsigned-diagnostic`. */
    object Diagnostic : SigningResolution // plain object: buildSrc's kotlin-dsl compiles at language 1.8 (no data object)
    /** Some but not all values set: always an error, never a silent diagnostic build. */
    data class Misconfigured(val missing: List<String>) : SigningResolution
}

object ReleaseSigning {
    val KEYS = listOf("LIVEAR_KEYSTORE", "LIVEAR_KEY_ALIAS", "LIVEAR_STORE_PASSWORD", "LIVEAR_KEY_PASSWORD")

    fun resolve(properties: Map<String, String>, env: Map<String, String>): SigningResolution {
        val values = KEYS.associateWith { k -> env[k]?.takeIf { it.isNotBlank() } ?: properties[k]?.takeIf { it.isNotBlank() } }
        val missing = values.filterValues { it == null }.keys.toList()
        return when (missing.size) {
            0 -> SigningResolution.Release(SigningInput(values.getValue(KEYS[0])!!, values.getValue(KEYS[1])!!, values.getValue(KEYS[2])!!, values.getValue(KEYS[3])!!))
            KEYS.size -> SigningResolution.Diagnostic
            else -> SigningResolution.Misconfigured(missing)
        }
    }

    /** Null when the build may go ahead; otherwise the error a requested release build must fail with. */
    fun problem(resolution: SigningResolution, requireSigning: Boolean): String? = when (resolution) {
        is SigningResolution.Release -> null
        is SigningResolution.Misconfigured -> "release signing is incomplete: ${resolution.missing.joinToString()} not set (keystore.properties or env)"
        SigningResolution.Diagnostic -> if (requireSigning) "release signing required (-Plivefit.requireSigning) but no keystore is configured" else null
    }
}

/**
 * True when the requested tasks build a release artifact of [projectPath] (so its release-only inputs must be present).
 * Every project's script is configured on each run, so `:glasses:assembleRelease` must not demand the phone's tile key:
 * a task qualified with another project's path doesn't count; unqualified names (`assembleRelease`, `assemble`) count for all.
 */
object ReleaseGate {
    private val AGGREGATES = setOf("assemble", "bundle", "build", "check16kb")

    fun requested(taskNames: List<String>, projectPath: String): Boolean = taskNames.any { t ->
        val owner = t.substringBeforeLast(':', missingDelimiterValue = "")
        if (owner.isNotEmpty() && owner != projectPath) return@any false
        val name = t.substringAfterLast(':')
        name.contains("Release") || name in AGGREGATES
    }
}

/** MapTiler key (spec §5): `LIVEAR_TILES_KEY` from env or local.properties; blank = missing; only [A-Za-z0-9_-] accepted. */
object TilesKey {
    const val NAME = "LIVEAR_TILES_KEY"
    private val VALID = Regex("[A-Za-z0-9_-]+")

    fun resolve(localProperties: Map<String, String>, env: Map<String, String>): String? {
        val raw = (env[NAME]?.takeIf { it.isNotBlank() } ?: localProperties[NAME]?.takeIf { it.isNotBlank() })?.trim() ?: return null
        require(VALID.matches(raw)) { "$NAME contains characters other than letters, digits, '_' and '-'" }
        return raw
    }
}
