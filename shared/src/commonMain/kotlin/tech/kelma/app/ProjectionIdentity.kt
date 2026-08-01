package tech.kelma.app

/** Compose-effect key that compares a large immutable snapshot by reference instead of walking it. */
internal class ProjectionIdentity(private val value: Any?) {
    override fun equals(other: Any?): Boolean =
        other is ProjectionIdentity && value === other.value

    override fun hashCode(): Int = 0
}
