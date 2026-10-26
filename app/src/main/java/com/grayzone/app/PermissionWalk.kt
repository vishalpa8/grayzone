package com.grayzone.app

/**
 * One pass through the permission popups. Pure so every combination of allow / decline /
 * "came back without granting" / "granted elsewhere" is unit-tested.
 *
 * Rules:
 *  - [steps] is fixed when the pass starts: the permissions that were missing then, in
 *    [SetupRequirements.SETUP_ORDER]. The "Step n of m" counter is based on it, so it never skips numbers.
 *  - The popup shown is the first step that is still missing and was not declined. Coming back from
 *    Settings without granting keeps the same popup; granting moves on; declining moves on.
 *  - The pass is over when [current] is null. It [succeeded] only if every step ended up granted.
 */
data class PermissionWalk(
    val steps: List<SetupRequirement>,
    val declined: Set<SetupRequirement> = emptySet(),
) {
    fun current(missing: Collection<SetupRequirement>): SetupRequirement? =
        steps.firstOrNull { it in missing && it !in declined }

    fun decline(requirement: SetupRequirement): PermissionWalk = copy(declined = declined + requirement)

    /** 1-based position of [requirement] and the total, for the "Step n of m" label. */
    fun position(requirement: SetupRequirement): Pair<Int, Int> = (steps.indexOf(requirement) + 1) to steps.size

    fun isFinished(missing: Collection<SetupRequirement>): Boolean = current(missing) == null

    fun succeeded(missing: Collection<SetupRequirement>): Boolean = steps.none { it in missing }

    companion object {
        /** A pass over everything in [missing] except [exclude]; null when there is nothing to ask. */
        fun start(
            missing: Collection<SetupRequirement>,
            exclude: Collection<SetupRequirement> = emptySet(),
        ): PermissionWalk? {
            val steps = SetupRequirements.SETUP_ORDER.filter { it in missing && it !in exclude }
            return if (steps.isEmpty()) null else PermissionWalk(steps)
        }
    }
}

/** Why a pass was started; decides what happens when it ends. */
enum class WalkReason {
    /** First launch: ask everything once; declines are remembered and not re-asked. */
    FIRST_RUN,
    /** A feature (Monitoring, an app toggle, Deep Work, a schedule) needs every permission. */
    FEATURE,
    /** Monitoring is on but a required permission was turned off outside the app. */
    RECOVERY,
}
