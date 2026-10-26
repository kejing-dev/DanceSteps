package io.embodit.dancesteps.domain.usecase

import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.PlaneSurface
import io.embodit.dancesteps.domain.model.SurfaceKind
import io.embodit.dancesteps.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The dance floor's place: centre on the floor plane and the heading of its "front" (yaw about the vertical, radians). */
data class FloorPose(val center: Vec3, val yaw: Float) {
    /** Unit vector of the floor's +z (ahead) in Stage space. */
    val forward: Vec3
        get() = Vec3(-sin(yaw), 0f, -cos(yaw))

    /** Unit vector of the floor's +x (right). */
    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    fun toStage(x: Float, z: Float, lift: Float = 0f): Vec3 = center + right * x + forward * z + Vec3(0f, lift, 0f)

    /** Floor-local (x, z) of a Stage point. */
    fun toLocal(p: Vec3): Pair<Float, Float> {
        val d = p - center
        return d.dot(right) to d.dot(forward)
    }

    companion object {
        /** Yaw of a horizontal heading. */
        fun yawOf(forward: Vec3, fallback: Float): Float =
            if (kotlin.math.hypot(forward.x, forward.z) < 0.05f) fallback else kotlin.math.atan2(-forward.x, -forward.z)
    }
}

enum class Side { FRONT, BACK, LEFT, RIGHT }

/** Which side of the floor is crowded by what, and how far to move away (design `+cramped`). */
data class Cramped(val side: Side, val what: SurfaceKind, val moveCm: Int)

data class PlaceCheck(
    val cramped: Cramped?,
    /** The front wall is nearer than 1.35 m: the mirror would not fit; how far to step back, cm. */
    val frontTooCloseCm: Int?,
) {
    val canPlace: Boolean
        get() = frontTooCloseCm == null

    companion object {
        val CLEAR = PlaceCheck(null, null)
    }
}

/**
 * Placement rule (design `rules.placeFloor`): the 1.5 m square under the head is checked against vertical
 * planes (walls, doors, windows) and raised horizontal semantic surfaces (tables, sofas, beds) whose footprint
 * crosses it. The deepest crossing names the side and the distance to move. A front wall within 1.35 m of
 * the centre blocks the placement.
 */
class PlaceFloorUseCase {

    fun check(pose: FloorPose, planes: Collection<PlaneSurface>, floorY: Float): PlaceCheck {
        var cramped: Cramped? = null
        var crampedDepth = 0f
        var frontWall = Float.MAX_VALUE
        val h = DanceRules.FLOOR_HALF_M
        for (p in planes) {
            if (p.kind == SurfaceKind.FLOOR || p.kind == SurfaceKind.CEILING || p.vertices.size < 3) continue
            val vertical = abs(p.normal.y) < 0.5f
            // raised surfaces between ankle and head height count; a vertical plane must reach above the knee
            if (!vertical && (p.minY < floorY + 0.05f || p.minY > floorY + 1.6f)) continue
            if (vertical && p.maxY < floorY + 0.2f) continue
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (v in p.vertices) {
                val (x, z) = pose.toLocal(v)
                minX = min(minX, x)
                maxX = max(maxX, x)
                minZ = min(minZ, z)
                maxZ = max(maxZ, z)
            }
            val wallLike = vertical && (p.kind == SurfaceKind.WALL || p.kind == SurfaceKind.DOOR || p.kind == SurfaceKind.WINDOW || p.kind == SurfaceKind.OTHER)
            if (wallLike && minX < h && maxX > -h && minZ > 0f && minZ < frontWall) frontWall = minZ
            val overlapX = min(maxX, h) - max(minX, -h)
            val overlapZ = min(maxZ, h) - max(minZ, -h)
            if (overlapX <= 0f || overlapZ <= 0f) continue
            // which edge is crossed least deeply: that is the side to move away from
            val candidates = listOf(Side.RIGHT to h - minX, Side.LEFT to maxX + h, Side.FRONT to h - minZ, Side.BACK to maxZ + h)
            val (side, depth) = candidates.minByOrNull { it.second } ?: continue
            if (depth <= 0f) continue
            if (cramped == null || depth > crampedDepth) {
                cramped = Cramped(side, p.kind, roundUp5(depth))
                crampedDepth = depth
            }
        }
        val tooClose = if (frontWall < DanceRules.FRONT_MIN_M) roundUp5(DanceRules.FRONT_MIN_M - frontWall) else null
        return PlaceCheck(cramped, tooClose)
    }

    private fun roundUp5(m: Float): Int = (ceil(m * 100f / 5f).toInt() * 5).coerceAtLeast(5)

    companion object {
        /** The dancer is off the floor when more than 0.2 m outside the square. */
        fun offFloor(local: Pair<Float, Float>): Boolean {
            val m = DanceRules.FLOOR_HALF_M + DanceRules.OFF_FLOOR_MARGIN_M
            return abs(local.first) > m || abs(local.second) > m
        }
    }
}
