package io.embodit.dancesteps.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import io.embodit.dancesteps.domain.model.MusicStyle
import io.embodit.dancesteps.platform.DanceLog
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The music and the beat clock (design `audio`): one long-lived streaming `AudioTrack` (48 kHz, stereo,
 * 16-bit) that a thread fills in 10 ms blocks. Everything is synthesised here (kick, bass, chords,
 * shaker, conga, clave, cowbell, guiro, metronome clicks); no audio assets. Silence is written while
 * nothing is armed; only going to the background pauses and flushes the stream.
 *
 * The clock: [arm] fixes beat 0 at a write-frame a little ahead of the write head. The frame being
 * played is `getTimestamp` extrapolated (refreshed every 500 ms; fallback: the playback head minus
 * 80 ms), so [beatPosition] is where the listener is in the schedule, in beats, and the scene drives
 * the footprints from it. The schedule is: `countIn` metronome beats, then `loops` loops of music.
 */
class BeatEngine {

    private class Schedule(
        val style: MusicStyle,
        val framesPerBeat: Double,
        val anchorFrame: Long,
        val countIn: Int,
        val beatsPerBar: Int,
        val loopBeats: Int,
        val totalBeats: Long,
        val metronomeOnly: Boolean,
    )

    private class Voice(val kind: Int, val start: Long, val gain: Float, val freq: Float, val dur: Float, val seed: Int)

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var schedule: Schedule? = null
    private val writeFrame = AtomicLong(0L)
    private val ts = AudioTimestamp()
    private var tsOk = false
    private var tsAtMs = 0L

    val armed: Boolean
        get() = schedule != null

    /** Opens the stream and starts the fill thread. Main thread; idempotent. */
    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, BLOCK * CHANNELS * 2 * 6)
        val t =
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }
                .onFailure { DanceLog.e("AUDIO track build failed: $it", it) }
                .getOrNull() ?: return
        track = t
        writeFrame.set(0L)
        tsOk = false
        runCatching { t.play() }.onFailure { DanceLog.e("AUDIO play failed: $it", it) }
        running = true
        thread =
            Thread({ fillLoop(t) }, "dance-audio").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
        DanceLog.i("AUDIO start buffer=$bufBytes min=$minBuf")
    }

    /** Pauses and flushes (background); [start] opens a fresh stream afterwards. Any schedule is dropped. */
    fun stop() {
        val t = track ?: return
        running = false
        schedule = null
        runCatching { thread?.join(500) }
        thread = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
        runCatching { t.release() }
        track = null
        DanceLog.i("AUDIO stop")
    }

    /**
     * Beat 0 lands [LEAD_MS] after the write head. [countIn] metronome beats, then [loops] loops of
     * [loopBeats] beats in [style] (or clicks only when [metronomeOnly]). Replaces any schedule.
     */
    fun arm(style: MusicStyle, bpm: Int, countIn: Int, beatsPerBar: Int, loopBeats: Int, loops: Int, metronomeOnly: Boolean = false) {
        val fpb = RATE * 60.0 / bpm.coerceAtLeast(20)
        val anchor = writeFrame.get() + (RATE * LEAD_MS / 1000)
        schedule = Schedule(style, fpb, anchor, countIn, beatsPerBar, loopBeats, countIn.toLong() + loops.toLong() * loopBeats, metronomeOnly)
        DanceLog.i("AUDIO arm style=$style bpm=$bpm countIn=$countIn loops=$loops anchor=$anchor metronome=$metronomeOnly")
    }

    /** Back to silence (the stream keeps running). */
    fun silence() {
        if (schedule != null) DanceLog.i("AUDIO silence")
        schedule = null
    }

    /** Beats since beat 0 of the current schedule as the listener hears it, or null when nothing is armed. */
    fun beatPosition(): Double? {
        val s = schedule ?: return null
        val play = playFrame() ?: return null
        return (play - s.anchorFrame) / s.framesPerBeat
    }

    /** Count-in beats of the current schedule (so that the scene knows where the music starts). */
    fun countIn(): Int = schedule?.countIn ?: 0

    private fun playFrame(): Double? {
        val t = track ?: return null
        val now = SystemClock.elapsedRealtime()
        if (now - tsAtMs > TS_REFRESH_MS) {
            tsAtMs = now
            tsOk = runCatching { t.getTimestamp(ts) }.getOrDefault(false)
        }
        if (tsOk) {
            val dt = (java.lang.System.nanoTime() - ts.nanoTime) / 1e9
            return ts.framePosition + dt * RATE
        }
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return null
        return head.toDouble() - RATE * FALLBACK_LATENCY_MS / 1000.0
    }

    // =========================================================================================
    // the audio thread
    // =========================================================================================

    private fun fillLoop(t: AudioTrack) {
        val buf = ShortArray(BLOCK * CHANNELS)
        val mix = FloatArray(BLOCK)
        val voices = ArrayList<Voice>()
        var last: Schedule? = null
        var nextBeat = 0L
        var frame = 0L
        while (running) {
            val s = schedule
            if (s !== last) {
                voices.clear()
                nextBeat = 0L
                last = s
            }
            java.util.Arrays.fill(mix, 0f)
            if (s != null) {
                // trigger the beats that fall into this block
                while (nextBeat < s.totalBeats) {
                    val f = s.anchorFrame + (nextBeat * s.framesPerBeat).toLong()
                    if (f >= frame + BLOCK) break
                    if (f >= frame - BLOCK) trigger(s, nextBeat, f, voices)
                    nextBeat++
                }
                render(voices, frame, mix)
            }
            for (i in 0 until BLOCK) {
                var v = mix[i]
                // soft clip
                if (v > 0.9f) v = 0.9f + (v - 0.9f) * 0.2f
                if (v < -0.9f) v = -0.9f + (v + 0.9f) * 0.2f
                val s16 = (v.coerceIn(-1f, 1f) * 32000f).toInt().toShort()
                buf[2 * i] = s16
                buf[2 * i + 1] = s16
            }
            val written = runCatching { t.write(buf, 0, buf.size) }.getOrDefault(-1)
            if (written < 0) {
                DanceLog.w("AUDIO write failed $written")
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    return
                }
            }
            frame += BLOCK
            writeFrame.set(frame)
        }
    }

    /** Adds the voices of beat [beat] (0-based from the count-in start) at write frame [f]. */
    private fun trigger(s: Schedule, beat: Long, f: Long, voices: MutableList<Voice>) {
        val fpb = s.framesPerBeat
        fun at(offsetBeats: Double, kind: Int, gain: Float, freq: Float = 0f, dur: Float = 0.2f) {
            voices += Voice(kind, f + (offsetBeats * fpb).toLong(), gain, freq, dur, (beat * 31 + kind).toInt())
        }
        if (beat < s.countIn) {
            val first = beat == 0L
            at(0.0, CLICK, if (first) 0.7f else 0.5f, if (first) 1500f else 1000f, 0.08f)
            return
        }
        if (s.metronomeOnly) {
            val inBar = ((beat - s.countIn) % s.beatsPerBar).toInt()
            at(0.0, CLICK, if (inBar == 0) 0.7f else 0.45f, if (inBar == 0) 1500f else 1000f, 0.08f)
            return
        }
        val musicBeat = beat - s.countIn
        val inBar = (musicBeat % s.beatsPerBar).toInt()
        val bar = ((musicBeat / s.beatsPerBar) % 2).toInt()
        when (s.style) {
            MusicStyle.WALTZ -> {
                // oom-pah-pah: bass + kick on 1, chords on 2 and 3; two chords alternate by bar
                val root = if (bar == 0) 130.8f else 98.0f
                if (inBar == 0) {
                    at(0.0, KICK, 0.8f, 0f, 0.3f)
                    at(0.0, BASS, 0.55f, root / 2f, 0.6f)
                } else {
                    at(0.0, CHORD, 0.38f, root * 2f, 0.55f)
                }
            }
            MusicStyle.RUMBA -> {
                // shaker on every eighth, conga 1 and 3, bass on 1 and 3; slow-quick-quick is what the counts say
                at(0.0, SHAKER, 0.22f, 0f, 0.08f)
                at(0.5, SHAKER, 0.14f, 0f, 0.07f)
                if (inBar == 0 || inBar == 2) at(0.0, BASS, if (inBar == 0) 0.6f else 0.45f, if (inBar == 0) 65.4f else 49f, 0.5f)
                if (inBar == 0) at(0.0, CONGA, 0.5f, 190f, 0.25f)
                if (inBar == 2) at(0.0, CONGA, 0.45f, 260f, 0.18f)
                if (inBar == 1 || inBar == 3) at(0.5, CONGA, 0.3f, 230f, 0.15f)
            }
            MusicStyle.SALSA -> {
                // cowbell every beat (accent 1 and 3), 2-3 clave over two bars, tumbao bass on the and-of-2 and on 4
                at(0.0, COWBELL, if (inBar == 0 || inBar == 2) 0.5f else 0.3f, 0f, 0.14f)
                if (bar == 0) {
                    if (inBar == 1) at(0.0, CLAVE, 0.55f, 0f, 0.06f)
                    if (inBar == 2) at(0.0, CLAVE, 0.55f, 0f, 0.06f)
                } else {
                    if (inBar == 0) at(0.0, CLAVE, 0.55f, 0f, 0.06f)
                    if (inBar == 1) at(0.5, CLAVE, 0.55f, 0f, 0.06f)
                    if (inBar == 3) at(0.0, CLAVE, 0.55f, 0f, 0.06f)
                }
                if (inBar == 1) at(0.5, BASS, 0.6f, 82.4f, 0.45f)
                if (inBar == 3) at(0.0, BASS, 0.6f, 65.4f, 0.55f)
                if (inBar == 0) at(0.0, KICK, 0.35f, 0f, 0.2f)
            }
            MusicStyle.CHACHA -> {
                // cowbell every beat, guiro on 4 and the and-of-4 (the cha-cha-cha), bass on 1 and 4-and
                at(0.0, COWBELL, if (inBar == 0) 0.5f else 0.32f, 0f, 0.14f)
                if (inBar == 3) {
                    at(0.0, GUIRO, 0.5f, 0f, 0.14f)
                    at(0.5, GUIRO, 0.5f, 0f, 0.14f)
                }
                if (inBar == 0) at(0.0, BASS, 0.6f, 73.4f, 0.5f)
                if (inBar == 3) {
                    at(0.0, BASS, 0.5f, 98f, 0.22f)
                    at(0.5, BASS, 0.5f, 110f, 0.22f)
                }
                if (inBar == 1) at(0.0, CONGA, 0.4f, 220f, 0.2f)
                if (inBar == 2) at(0.5, CONGA, 0.3f, 300f, 0.12f)
            }
        }
    }

    private fun render(voices: MutableList<Voice>, frame: Long, mix: FloatArray) {
        var i = voices.size - 1
        while (i >= 0) {
            val v = voices[i]
            val end = v.start + (v.dur * RATE).toLong()
            if (end <= frame) {
                voices.removeAt(i)
                i--
                continue
            }
            val from = max(0L, v.start - frame).toInt()
            val to = min(BLOCK.toLong(), end - frame).toInt()
            var noise = v.seed * 1103515245 + 12345
            for (k in from until to) {
                val t = (frame + k - v.start).toFloat() / RATE
                noise = noise * 1103515245 + 12345
                val n = ((noise ushr 8) and 0xFFFF) / 32768f - 1f
                val sample =
                    when (v.kind) {
                        CLICK -> sin(TWO_PI * v.freq * t) * exp(-t * 60f)
                        KICK -> sin(TWO_PI * (50f * t + 110f * (1f - exp(-t * 20f)) / 20f * 20f)) * exp(-t * 14f)
                        BASS -> tri(v.freq * t) * min(1f, t / 0.006f) * exp(-t * 5f)
                        CHORD -> (tri(v.freq * t) + tri(v.freq * 1.26f * t) + tri(v.freq * 1.5f * t)) / 3f * min(1f, t / 0.01f) * exp(-t * 4.5f)
                        SHAKER -> n * exp(-t * 55f)
                        CONGA -> sin(TWO_PI * v.freq * (t + 0.004f * exp(-t * 30f))) * exp(-t * 18f)
                        CLAVE -> sin(TWO_PI * 2500f * t) * exp(-t * 90f)
                        COWBELL -> (sq(560f * t) + sq(845f * t)) * 0.5f * exp(-t * 22f)
                        GUIRO -> n * (0.5f + 0.5f * sin(TWO_PI * 70f * t)) * exp(-t * 16f)
                        else -> 0f
                    }
                mix[k] += sample * v.gain
            }
            i--
        }
    }

    private fun tri(x: Float): Float {
        val p = x - kotlin.math.floor(x)
        return 4f * kotlin.math.abs(p - 0.5f) - 1f
    }

    private fun sq(x: Float): Float = if (x - kotlin.math.floor(x) < 0.5f) 1f else -1f

    companion object {
        const val RATE = 48000
        private const val CHANNELS = 2
        private const val BLOCK = 480
        private const val LEAD_MS = 120
        private const val TS_REFRESH_MS = 500L
        private const val FALLBACK_LATENCY_MS = 80
        private const val TWO_PI = (2 * PI).toFloat()

        private const val CLICK = 1
        private const val KICK = 2
        private const val BASS = 3
        private const val CHORD = 4
        private const val SHAKER = 5
        private const val CONGA = 6
        private const val CLAVE = 7
        private const val COWBELL = 8
        private const val GUIRO = 9
    }
}
