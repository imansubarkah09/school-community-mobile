package space.schoolcommunity.app.update

enum class UpdateType { NONE, OPTIONAL, MANDATORY }

/** Result of comparing the installed build against the latest published release. */
data class UpdateDecision(val type: UpdateType, val release: Release?) {
    companion object {
        val NONE = UpdateDecision(UpdateType.NONE, null)

        /**
         * Compare versionCode integers only — never version name strings.
         *
         * - release == null (API down / no release)      -> NONE  (never blocks)
         * - latest <= installed                          -> NONE
         * - forceUpdate, or installed < minimumSupported -> MANDATORY
         * - otherwise                                    -> OPTIONAL
         */
        fun of(installedVersionCode: Long, release: Release?): UpdateDecision {
            if (release == null || release.versionCode <= installedVersionCode) return NONE
            val mandatory =
                release.forceUpdate || installedVersionCode < release.minimumSupportedVersion
            return UpdateDecision(
                if (mandatory) UpdateType.MANDATORY else UpdateType.OPTIONAL,
                release,
            )
        }
    }
}
