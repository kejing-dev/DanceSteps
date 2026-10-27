package io.embodit.dancesteps.ui.dance.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.Foot
import io.embodit.dancesteps.domain.model.Pattern
import io.embodit.dancesteps.domain.model.Quat4
import io.embodit.dancesteps.domain.model.Vec3
import io.embodit.dancesteps.domain.usecase.CenterPathUseCase
import io.embodit.dancesteps.domain.usecase.FloorPose
import io.embodit.dancesteps.domain.usecase.Side
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The dance floor frame (design `entities3d.danceFloor`): a 1.5 m square of white dashed tape (30 mm
 * wide, 2 mm thick, 120 mm dashes with 60 mm gaps) and a 20 cm "front" chevron at the middle of the
 * front edge. 34 boxes under one group entity that carries the floor pose. The crowded edge turns orange.
 */
internal class DanceFloorView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {
    private val group = Entity().named("danceFloor")
    private val edges = HashMap<Side, ArrayList<ModelEntity>>()
    private var alertSide: Side? = null
    private var placed = false
    private var shown = false
    private var opacity = -1f

    suspend fun prepare(cube: MeshResource) {
        root.addChild(group)
        val dash = cube
        val dashScale = Vec3(DanceRules.TAPE_DASH_M, DanceRules.TAPE_THICK_M, DanceRules.TAPE_WIDTH_M)
        val h = DanceRules.FLOOR_HALF_M
        val n = 8
        val span = n * DanceRules.TAPE_DASH_M + (n - 1) * DanceRules.TAPE_GAP_M
        val start = -span / 2f + DanceRules.TAPE_DASH_M / 2f
        val quarter = Quat4.axisAngle(Vec3.UP, (PI / 2).toFloat())
        for (side in Side.entries) {
            val list = ArrayList<ModelEntity>()
            for (i in 0 until n) {
                val along = start + i * (DanceRules.TAPE_DASH_M + DanceRules.TAPE_GAP_M)
                val e = ModelEntity(dash, materials.tapePlacing)
                e.named("tape_${side.name.lowercase()}_$i")
                group.addChild(e)
                when (side) {
                    // local x = right, local z = -ahead (the group is turned so that -z is the floor's forward)
                    Side.FRONT -> e.setLocal(position = Vec3(along, 0f, -h), scale = dashScale)
                    Side.BACK -> e.setLocal(position = Vec3(along, 0f, h), scale = dashScale)
                    Side.LEFT -> e.setLocal(position = Vec3(-h, 0f, along), rotation = quarter, scale = dashScale)
                    Side.RIGHT -> e.setLocal(position = Vec3(h, 0f, along), rotation = quarter, scale = dashScale)
                }
                list += e
            }
            edges[side] = list
        }
        // the chevron: two 0.2 m legs meeting at the middle of the front edge, pointing ahead
        val leg = cube
        val legScale = Vec3(DanceRules.FRONT_ARROW_M, DanceRules.TAPE_THICK_M, DanceRules.TAPE_WIDTH_M)
        val tip = Vec3(0f, 0f, -h - 0.06f)
        for (sign in listOf(-1f, 1f)) {
            val e = ModelEntity(leg, materials.tapePlacing)
            e.named(if (sign < 0) "arrow_l" else "arrow_r")
            group.addChild(e)
            val a = sign * (PI / 4).toFloat()
            // the leg runs from the tip back towards the floor
            val centre = tip + Vec3(sign * DanceRules.FRONT_ARROW_M / 2f * 0.7071f, 0f, DanceRules.FRONT_ARROW_M / 2f * 0.7071f)
            e.setLocal(position = centre, rotation = Quat4.axisAngle(Vec3.UP, -a), scale = legScale)
            edges.getOrPut(Side.FRONT) { ArrayList() } += e
        }
        group.enabled = false
    }

    fun setPose(pose: FloorPose, floorY: Float) {
        group.setLocal(position = Vec3(pose.center.x, floorY + DanceRules.TAPE_THICK_M / 2f, pose.center.z), rotation = Quat4.axisAngle(Vec3.UP, pose.yaw))
    }

    /** Placing (60 %) or placed (35 %); [alert] names the crowded edge while placing. */
    fun setStyle(placed: Boolean, alert: Side?) {
        if (placed != this.placed || alert != alertSide) {
            this.placed = placed
            alertSide = alert
            val base = if (placed) materials.tapePlaced else materials.tapePlacing
            for ((side, list) in edges) {
                val m = if (!placed && side == alert) materials.tapeAlert else base
                list.forEach { it.setMaterial(m) }
            }
        }
    }

    fun setShown(on: Boolean) {
        if (on != shown) {
            shown = on
            group.enabled = on
        }
    }

    fun destroy() {
        runCatching { group.destroy(true) }
    }
}

/**
 * One footprint (design `entities3d.footprint`, 0.1.1): the bare-sole outline of [FootShape] with its five
 * toes, big toe inside, so a left and a right foot are mirror images and the toes point the way. Two
 * entities: the fill and its outline, so that a level can show either or both (the next step: half fill
 * under a white edge).
 */
/** The four footprint meshes, built once per scene. */
internal class FootMeshes(val fillL: MeshResource, val fillR: MeshResource, val edgeL: MeshResource, val edgeR: MeshResource)

internal class FootprintView(parent: Entity, private val materials: SceneMaterials, shapes: FootMeshes, val left: Boolean, name: String) {
    val group = Entity().named(name)
    private val fill: ModelEntity
    private val edge: ModelEntity
    private var level: FootLevel? = null
    private var shown = true

    init {
        parent.addChild(group)
        fill = ModelEntity(if (left) shapes.fillL else shapes.fillR, materials.footFill(left, FootLevel.FULL))
        edge = ModelEntity(if (left) shapes.edgeL else shapes.edgeR, materials.footFill(left, FootLevel.OUTLINE))
        group.addChild(fill)
        group.addChild(edge)
        fill.setLocal(position = Vec3(0f, DanceRules.FOOT_LIFT, 0f))
        edge.setLocal(position = Vec3(0f, DanceRules.FOOT_LIFT + 0.0005f, 0f))
        setLevel(FootLevel.OUTLINE)
    }

    /** Local place on the floor group: (x, z) floor metres, toes along +z of the floor (= -z of the group). */
    fun place(x: Float, z: Float, yaw: Float) {
        group.setLocal(position = Vec3(x, 0f, -z), rotation = Quat4.axisAngle(Vec3.UP, yaw))
    }

    fun setLevel(level: FootLevel) {
        if (level == this.level) return
        this.level = level
        val showFill = level == FootLevel.FULL || level == FootLevel.NEXT || level == FootLevel.DONE
        val showEdge = level == FootLevel.OUTLINE || level == FootLevel.STANCE || level == FootLevel.NEXT
        fill.enabled = showFill
        if (showFill) fill.setMaterial(materials.footFill(left, level))
        edge.enabled = showEdge
        if (showEdge) edge.setMaterial(if (level == FootLevel.NEXT) materials.footNextEdge else materials.footFill(left, level))
    }

    fun setShown(on: Boolean) {
        if (on != shown) {
            shown = on
            group.enabled = on
        }
    }

    fun setOpacity(o: Float) = group.setOpacity(o)

    fun destroy() = runCatching { group.destroy(true) }
}

/**
 * Everything that lies on the placed floor and belongs to the pattern: the footprints of its landing spots,
 * the two start-stance outlines, the beat rings, the step arrow (from where the moving foot stands to where it
 * lands, in its colour), the gold expected-centre route and the gold weight dot on it. All of it
 * hangs under one group that carries the floor pose, so a new pattern only re-lays the children.
 */
internal class PatternView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {
    val group = Entity().named("pattern")
    private var footMeshes: FootMeshes? = null
    private var weightDot: ModelEntity? = null
    private var bandMesh: MeshResource? = null
    private var pulseMesh: MeshResource? = null
    private val feet = ArrayList<FootprintView>()
    private val stance = ArrayList<FootprintView>()
    private val pulses = ArrayList<ModelEntity>()
    private val pulseStart = LongArray(2) { NEVER }
    private val pulseSpot = IntArray(2) { -1 }
    private var nextPulse = 0
    private val arrow = ArrayList<Strip>()
    private val path = ArrayList<Strip>()
    private var pattern: Pattern? = null
    private var shown = false
    private var stanceOn = false
    private var stancePhase = 0f

    suspend fun prepare(band: MeshResource) {
        root.addChild(group)
        val fillL = pacedMesh { shared.keep(MeshFactory.footFill(true, "foot_fill_l")) }
        val fillR = pacedMesh { shared.keep(MeshFactory.footFill(false, "foot_fill_r")) }
        val edgeL = pacedMesh { shared.keep(MeshFactory.footStroke(true, "foot_edge_l")) }
        val edgeR = pacedMesh { shared.keep(MeshFactory.footStroke(false, "foot_edge_r")) }
        footMeshes = FootMeshes(fillL, fillR, edgeL, edgeR)
        val weightMesh = pacedMesh { shared.keep(MeshFactory.disc(DanceRules.WEIGHT_DOT_R, "weight_disc", segments = 28)) }
        weightDot = ModelEntity(weightMesh, materials.weight).also { it.named("weightDot"); group.addChild(it); it.enabled = false }
        bandMesh = band
        pulseMesh = pacedMesh { shared.keep(MeshFactory.ring(0.9f, 1f, "pulse_ring", segments = 40)) }
        for (i in 0 until 2) {
            val e = ModelEntity(pulseMesh!!, materials.pulseL)
            e.named("pulse$i")
            group.addChild(e)
            e.enabled = false
            pulses += e
        }
        for (i in 0 until 3) arrow += Strip(group, bandMesh!!, materials.arrowL, "arrow$i")
        for (i in 0 until PATH_POOL) path += Strip(group, bandMesh!!, materials.path, "path$i")
        group.enabled = false
    }

    fun setPose(pose: FloorPose, floorY: Float) {
        group.setLocal(position = Vec3(pose.center.x, floorY, pose.center.z), rotation = Quat4.axisAngle(Vec3.UP, pose.yaw))
    }

    /** Lays the footprints, the stance and the route of [p] (idempotent for the same pattern). */
    fun setPattern(p: Pattern) {
        if (p === pattern) return
        pattern = p
        val shapes = footMeshes ?: return
        feet.forEach { it.destroy() }
        feet.clear()
        stance.forEach { it.destroy() }
        stance.clear()
        val (cx, cz) = p.boundsCenter
        for (s in p.spots) {
            feet += FootprintView(group, materials, shapes, s.foot == Foot.L, "foot${s.index}").also { it.place(s.x - cx, s.z - cz, s.yaw) }
        }
        stance += FootprintView(group, materials, shapes, true, "stanceL").also { it.place(p.startL.first - cx, p.startL.second - cz, 0f); it.setLevel(FootLevel.STANCE) }
        stance += FootprintView(group, materials, shapes, false, "stanceR").also { it.place(p.startR.first - cx, p.startR.second - cz, 0f); it.setLevel(FootLevel.STANCE) }
        layRoute(p, cx, cz)
        for (a in arrow) a.hide()
        weightDot?.enabled = false
    }

    /** The pattern's offset so that its bounding box is centred on the floor (design `patterns.frame`). */
    fun centerOffset(): Pair<Float, Float> = pattern?.boundsCenter ?: (0f to 0f)

    private fun layRoute(p: Pattern, cx: Float, cz: Float) {
        val pts = CenterPathUseCase(p).sample(16)
        // walk the closed polyline laying 50 mm dashes with 40 mm gaps
        var k = 0
        var carry = 0f
        var dashOn = true
        var dashLeft = DanceRules.PATH_DASH_M
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val len = hypot(b.first - a.first, b.second - a.second)
            if (len < 1e-5f) continue
            var t = 0f
            while (t < len) {
                val seg = minOf(len - t, dashLeft)
                if (dashOn && k < path.size && seg > 0.004f) {
                    val x0 = a.first + (b.first - a.first) * (t / len) - cx
                    val z0 = a.second + (b.second - a.second) * (t / len) - cz
                    val x1 = a.first + (b.first - a.first) * ((t + seg) / len) - cx
                    val z1 = a.second + (b.second - a.second) * ((t + seg) / len) - cz
                    path[k].draw(Vec3(x0, PATH_LIFT, -z0), Vec3(x1, PATH_LIFT, -z1), DanceRules.PATH_WIDTH_M, Vec3.UP)
                    k++
                }
                t += seg
                dashLeft -= seg
                if (dashLeft <= 1e-5f) {
                    dashOn = !dashOn
                    dashLeft = if (dashOn) DanceRules.PATH_DASH_M else DanceRules.PATH_GAP_M
                }
            }
            carry += len
        }
        for (i in k until path.size) path[i].hide()
    }

    fun setShown(on: Boolean) {
        if (on != shown) {
            shown = on
            group.enabled = on
        }
    }

    /** Ready / summary: every footprint dim; summary hides them (only route and trail remain). */
    fun setFeetShown(on: Boolean) = feet.forEach { it.setShown(on) }

    private var routeShown = true

    fun setRouteShown(on: Boolean) {
        if (on == routeShown) return
        routeShown = on
        for (s in path) if (!on) s.hide()
        if (on) pattern?.let { p -> val (cx, cz) = p.boundsCenter; layRoute(p, cx, cz) }
    }

    fun setAllLevel(level: FootLevel) = feet.forEach { it.setLevel(level) }

    /**
     * Dance: [current] full, [next] half under a white edge, spots already stepped on this loop at 15 %, the
     * rest outlines. The arrow runs from where the foot of [next] stands now to where it lands (none for an
     * in-place step: the weight dot shows that shift).
     */
    fun setDance(p: Pattern, current: Int?, next: Int?, beatInLoop: Float) {
        val currentSpot = current?.let { p.spotOf(it) }
        val nextSpot = next?.let { p.spotOf(it) }
        for (s in p.spots) {
            val done = s.stepIndices.any { p.steps[it].beat < beatInLoop - 1e-4f && it != current }
            val level =
                when {
                    s.index == currentSpot -> FootLevel.FULL
                    s.index == nextSpot -> FootLevel.NEXT
                    done -> FootLevel.DONE
                    else -> FootLevel.OUTLINE
                }
            feet.getOrNull(s.index)?.setLevel(level)
        }
        val (cx, cz) = p.boundsCenter
        if (next != null && !p.inPlace(next)) {
            val (fx, fz) = p.footBefore(next)
            val to = p.steps[next]
            val m = if (to.foot == Foot.L) materials.arrowL else materials.arrowR
            if (m !== arrowMaterial) {
                arrowMaterial = m
                arrow.forEach { it.setMaterial(m) }
            }
            drawArrow(Vec3(fx - cx, ARROW_LIFT, -(fz - cz)), Vec3(to.x - cx, ARROW_LIFT, -(to.z - cz)))
        } else {
            for (a in arrow) a.hide()
        }
    }

    private var arrowMaterial: Material? = null

    /** The weight dot at the expected centre of mass (pattern coordinates), or hidden for null. */
    fun setWeight(at: Pair<Float, Float>?) {
        val dot = weightDot ?: return
        if (at == null) {
            if (dot.enabled) dot.enabled = false
            return
        }
        val (cx, cz) = pattern?.boundsCenter ?: (0f to 0f)
        dot.setLocal(position = Vec3(at.first - cx, WEIGHT_LIFT, -(at.second - cz)))
        if (!dot.enabled) dot.enabled = true
    }

    private fun drawArrow(from: Vec3, to: Vec3) {
        val full = to - from
        val len = full.length
        if (len < 0.05f) {
            for (a in arrow) a.hide()
            return
        }
        val dir = full / len
        // from just past the foot's centre to just short of the landing centre
        val a = from + dir * 0.05f
        val b = to - dir * 0.06f
        if ((b - a).dot(dir) < 0.06f) {
            for (s in arrow) s.hide()
            return
        }
        arrow[0].draw(a, b, DanceRules.ARROW_WIDTH_M, Vec3.UP)
        val side = Vec3.UP.cross(dir).normalized()
        val head = DanceRules.ARROW_HEAD_M
        arrow[1].draw(b, b - dir * head + side * head, DanceRules.ARROW_WIDTH_M, Vec3.UP)
        arrow[2].draw(b, b - dir * head - side * head, DanceRules.ARROW_WIDTH_M, Vec3.UP)
    }

    /** A ring spreads from the footprint of [step] to 1.6 times its size in 0.25 s. */
    fun pulse(p: Pattern, step: Int, nowMs: Long) {
        val spot = p.spots.getOrNull(p.spotOf(step)) ?: return
        val i = nextPulse
        nextPulse = (nextPulse + 1) % pulses.size
        val (cx, cz) = p.boundsCenter
        val e = pulses[i]
        e.setMaterial(if (spot.foot == Foot.L) materials.pulseL else materials.pulseR)
        e.setLocal(position = Vec3(spot.x - cx, DanceRules.FOOT_LIFT + 0.002f, -(spot.z - cz)))
        pulseStart[i] = nowMs
        pulseSpot[i] = spot.index
        e.enabled = true
    }

    /** Once per frame: the spreading rings and the breathing start stance. */
    fun onFrame(nowMs: Long, stanceBlinkHz: Float) {
        for (i in pulses.indices) {
            val t0 = pulseStart[i]
            if (t0 == NEVER) continue
            val q = (nowMs - t0) / PULSE_MS
            if (q >= 1f) {
                pulses[i].enabled = false
                pulseStart[i] = NEVER
                continue
            }
            val r = DanceRules.FOOT_LENGTH / 2f * (1f + 0.6f * q)
            pulses[i].setLocal(scale = Vec3(r * 0.75f, 1f, r))
            pulses[i].setOpacity(1f - q)
        }
        if (stanceOn) {
            val o = 0.45f + 0.55f * (0.5f + 0.5f * sin(2f * PI.toFloat() * stanceBlinkHz * nowMs / 1000f))
            if (kotlin.math.abs(o - stancePhase) > 0.03f) {
                stancePhase = o
                stance.forEach { it.setOpacity(o) }
            }
        }
    }

    fun setStance(on: Boolean) {
        if (on == stanceOn) return
        stanceOn = on
        stance.forEach { it.setShown(on) }
        if (!on) stance.forEach { it.setOpacity(1f) }
    }

    fun hideCues() {
        for (a in arrow) a.hide()
        weightDot?.enabled = false
    }

    fun hidePulses() {
        for (i in pulses.indices) {
            pulses[i].enabled = false
            pulseStart[i] = NEVER
        }
    }

    fun destroy() {
        feet.forEach { it.destroy() }
        stance.forEach { it.destroy() }
        arrow.forEach { it.destroy() }
        path.forEach { it.destroy() }
        runCatching { group.destroy(true) }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val PULSE_MS = 250f
        private const val PATH_POOL = 64
        private const val PATH_LIFT = 0.0015f
        private const val ARROW_LIFT = 0.0035f
        private const val WEIGHT_LIFT = 0.004f
    }
}

/**
 * You on the floor (design `entities3d.youDot`, `trail`): a white disc of 120 mm with a thin interaction-yellow
 * ring at the head's floor point (0.1 s exponential smoothing), and your trail: a 12 mm white strip between
 * points taken every 100 ms. While dancing the last two loops stay (at most 160 segments) in four fade tiers,
 * retuned every 0.5 s; the summary shows the whole run downsampled to at most 400 segments. Stage metres.
 */
internal class YouView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {
    private val group = Entity().named("you")
    private var dot: ModelEntity? = null
    private var ring: ModelEntity? = null
    private val segments = ArrayList<Strip>()
    private val tiers = IntArray(DanceRules.TRAIL_POOL) { -1 }
    private val points = ArrayList<Vec3>()
    private var liveFrom = 0
    private var lastTierMs = 0L
    private var shownDot = false
    private var smooth: Vec3? = null
    private var summaryMode = false

    suspend fun prepare(band: MeshResource) {
        root.addChild(group)
        val disc = pacedMesh { shared.keep(MeshFactory.disc(DanceRules.YOU_DOT_R, "you_disc", segments = 36)) }
        val ringMesh = pacedMesh { shared.keep(MeshFactory.ring(DanceRules.YOU_DOT_R + 0.004f, DanceRules.YOU_DOT_R + 0.012f, "you_ring", segments = 48)) }
        dot = ModelEntity(disc, materials.you).also { it.named("youDot"); group.addChild(it); it.enabled = false }
        ring = ModelEntity(ringMesh, materials.youRing).also { it.named("youRing"); group.addChild(it); it.enabled = false }
        for (i in 0 until DanceRules.TRAIL_POOL) segments += Strip(group, band, materials.trail[0], "trail$i")
    }

    /** The smoothed dot position (Stage), or null before the first sample. */
    val position: Vec3?
        get() = smooth

    /** Moves the dot to [foot] (Stage floor point), smoothing over about 0.1 s. */
    fun place(foot: Vec3, dtS: Float) {
        val s = smooth
        val k = (dtS / 0.1f).coerceIn(0f, 1f)
        val p = if (s == null) foot else s + (foot - s) * k
        smooth = p
        dot?.setLocal(position = p + Vec3(0f, DOT_LIFT, 0f))
        ring?.setLocal(position = p + Vec3(0f, DOT_LIFT + 0.0005f, 0f))
    }

    fun setDotShown(on: Boolean) {
        if (on == shownDot) return
        shownDot = on
        dot?.enabled = on
        ring?.enabled = on
    }

    /** Adds a trail point (every 100 ms while dancing). */
    fun addPoint(p: Vec3, nowMs: Long) {
        points += p
        if (points.size >= 2) {
            val i = points.size - 2
            if (i < segments.size) {
                segments[i].draw(points[i] + Vec3(0f, TRAIL_LIFT, 0f), points[i + 1] + Vec3(0f, TRAIL_LIFT, 0f), DanceRules.TRAIL_WIDTH_M, Vec3.UP)
            } else {
                // the pool is full: re-lay the live window downsampled
                relayLive()
            }
        }
        if (!summaryMode) {
            val maxLive = DanceRules.TRAIL_LIVE_SEGMENTS
            if (points.size - 1 - liveFrom > maxLive) liveFrom = points.size - 1 - maxLive
            if (nowMs - lastTierMs >= 500L) {
                lastTierMs = nowMs
                retier()
            }
        }
    }

    /** The live window: segments before [liveFrom] hidden, the rest in four tiers from old to new. */
    private fun retier() {
        val n = points.size - 1
        for (i in 0 until minOf(n, segments.size)) {
            val tier =
                if (i < liveFrom) -1
                else {
                    val age = (n - 1 - i).toFloat() / (n - liveFrom).coerceAtLeast(1)
                    (age * 4f).toInt().coerceIn(0, 3)
                }
            if (tier != tiers[i]) {
                tiers[i] = tier
                if (tier < 0) segments[i].hide() else {
                    segments[i].setMaterial(materials.trail[tier])
                    segments[i].draw(points[i] + Vec3(0f, TRAIL_LIFT, 0f), points[i + 1] + Vec3(0f, TRAIL_LIFT, 0f), DanceRules.TRAIL_WIDTH_M, Vec3.UP)
                }
            }
        }
    }

    private fun relayLive() {
        val keep = points.subList((points.size - DanceRules.TRAIL_LIVE_SEGMENTS - 1).coerceAtLeast(0), points.size).toList()
        points.clear()
        points.addAll(keep)
        liveFrom = 0
        for (i in segments.indices) {
            tiers[i] = -1
            segments[i].hide()
        }
        for (i in 0 until points.size - 1) segments[i].draw(points[i] + Vec3(0f, TRAIL_LIFT, 0f), points[i + 1] + Vec3(0f, TRAIL_LIFT, 0f), DanceRules.TRAIL_WIDTH_M, Vec3.UP)
        retier()
    }

    /** Drops the points recorded since [count] points were on record (a paused loop is not kept). */
    fun truncate(count: Int) {
        while (points.size > count) points.removeAt(points.size - 1)
        liveFrom = liveFrom.coerceAtMost((points.size - 1).coerceAtLeast(0))
        for (i in segments.indices) {
            if (i >= points.size - 1) {
                segments[i].hide()
                tiers[i] = -1
            }
        }
        retier()
    }

    val pointCount: Int
        get() = points.size

    /** Summary: the whole run, every segment at full white, downsampled to the pool. */
    fun showWhole(all: List<Vec3>) {
        summaryMode = true
        points.clear()
        val step = if (all.size - 1 > segments.size) (all.size - 1).toFloat() / segments.size else 1f
        var t = 0f
        while (t < all.size) {
            points += all[t.toInt()]
            t += step
        }
        if (points.lastOrNull() !== all.lastOrNull() && all.isNotEmpty()) points += all.last()
        for (i in segments.indices) {
            if (i < points.size - 1) {
                segments[i].setMaterial(materials.trail[0])
                segments[i].draw(points[i] + Vec3(0f, TRAIL_LIFT, 0f), points[i + 1] + Vec3(0f, TRAIL_LIFT, 0f), DanceRules.TRAIL_WIDTH_M, Vec3.UP)
                tiers[i] = 0
            } else {
                segments[i].hide()
                tiers[i] = -1
            }
        }
    }

    fun clearTrail() {
        summaryMode = false
        points.clear()
        liveFrom = 0
        for (i in segments.indices) {
            segments[i].hide()
            tiers[i] = -1
        }
    }

    fun destroy() {
        segments.forEach { it.destroy() }
        runCatching { group.destroy(true) }
    }

    private companion object {
        const val DOT_LIFT = 0.004f
        const val TRAIL_LIFT = 0.0025f
    }
}
