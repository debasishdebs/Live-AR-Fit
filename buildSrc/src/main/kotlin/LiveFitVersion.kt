/** Spec §2: one versionName for all three apps; versionCode = major*1_000_000 + minor*10_000 + patch*100 + formFactor. */
object LiveFitVersion {
    enum class FormFactor(val digit: Int) { Phone(0), Watch(1), Glasses(2) }

    private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?""")

    fun code(versionName: String, formFactor: FormFactor): Int {
        val m = requireNotNull(SEMVER.matchEntire(versionName)) { "livefit.version '$versionName' must be MAJOR.MINOR.PATCH[-suffix]" }
        val (major, minor, patch) = m.groupValues.drop(1).take(3).map { it.toInt() }
        require(minor <= 99 && patch <= 99) { "livefit.version '$versionName': minor and patch must be 0..99" }
        require(major <= 2_099) { "livefit.version '$versionName': major must be <= 2099 (Play's versionCode limit)" }
        return major * 1_000_000 + minor * 10_000 + patch * 100 + formFactor.digit
    }
}
