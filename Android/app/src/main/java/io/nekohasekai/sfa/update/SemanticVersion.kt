package io.nekohasekai.sfa.update

/** A small, strict SemVer 2.0 comparator with optional release-tag prefixes (`v1.2.3`). */
internal class SemanticVersion private constructor(
    private val major: String,
    private val minor: String,
    private val patch: String,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareNumeric(major, other.major).takeIf { it != 0 }?.let { return it }
        compareNumeric(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareNumeric(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty()) return if (other.prerelease.isEmpty()) 0 else 1
        if (other.prerelease.isEmpty()) return -1

        val sharedSize = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until sharedSize) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumeric = left.isAsciiNumeric()
            val rightNumeric = right.isAsciiNumeric()
            val comparison = when {
                leftNumeric && rightNumeric -> compareNumeric(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun compare(left: String, right: String): Int? {
            val leftVersion = parse(left) ?: return null
            val rightVersion = parse(right) ?: return null
            return leftVersion.compareTo(rightVersion)
        }

        fun parse(rawVersion: String): SemanticVersion? {
            var version = rawVersion.trim()
            if (version.startsWith("v", ignoreCase = true)) {
                version = version.drop(1)
            }

            val buildSeparator = version.indexOf('+')
            if (buildSeparator >= 0) {
                if (version.indexOf('+', buildSeparator + 1) >= 0) return null
                val build = version.substring(buildSeparator + 1)
                if (!build.isValidIdentifierList(allowLeadingZero = true)) return null
                version = version.substring(0, buildSeparator)
            }

            val prereleaseSeparator = version.indexOf('-')
            val prerelease = if (prereleaseSeparator >= 0) {
                val value = version.substring(prereleaseSeparator + 1)
                if (!value.isValidIdentifierList(allowLeadingZero = false)) return null
                version = version.substring(0, prereleaseSeparator)
                value.split('.')
            } else {
                emptyList()
            }

            val core = version.split('.')
            if (core.size != 3 || core.any { !it.isValidCoreNumber() }) return null
            return SemanticVersion(core[0], core[1], core[2], prerelease)
        }

        private fun compareNumeric(left: String, right: String): Int {
            val lengthComparison = left.length.compareTo(right.length)
            return if (lengthComparison != 0) lengthComparison else left.compareTo(right)
        }

        private fun String.isValidCoreNumber(): Boolean = isAsciiNumeric() && (length == 1 || first() != '0')

        private fun String.isValidIdentifierList(allowLeadingZero: Boolean): Boolean = split('.').all { identifier ->
            identifier.isNotEmpty() &&
                identifier.all { character ->
                    character in '0'..'9' ||
                        character in 'A'..'Z' ||
                        character in 'a'..'z' ||
                        character == '-'
                } &&
                (
                    allowLeadingZero ||
                        !identifier.isAsciiNumeric() ||
                        identifier.length == 1 ||
                        identifier.first() != '0'
                    )
        }

        private fun String.isAsciiNumeric(): Boolean = isNotEmpty() && all { it in '0'..'9' }
    }
}
