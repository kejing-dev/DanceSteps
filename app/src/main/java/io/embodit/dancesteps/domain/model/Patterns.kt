package io.embodit.dancesteps.domain.model

/** The four v1 patterns, copied from the design package `patterns.list` (leader steps; floor metres). */
object Patterns {
    val waltzBox =
        Pattern(
            id = "waltzBox",
            meter = "3/4",
            bpm = 87,
            beatsPerBar = 3,
            loopBeats = 6,
            counts = "1 2 3 · 1 2 3",
            startL = -0.08f to 0f,
            startR = 0.08f to 0f,
            steps =
                listOf(
                    Step(0f, "1", Foot.L, -0.08f, 0.40f),
                    Step(1f, "2", Foot.R, 0.38f, 0.40f),
                    Step(2f, "3", Foot.L, 0.22f, 0.40f),
                    Step(3f, "1", Foot.R, 0.38f, 0.00f),
                    Step(4f, "2", Foot.L, -0.08f, 0.00f),
                    Step(5f, "3", Foot.R, 0.08f, 0.00f),
                ),
            pauses = emptyList(),
            music = MusicStyle.WALTZ,
        )

    /** Rumba counts are words: S = slow, Q = quick (localised on the mirror). */
    val rumbaBox =
        Pattern(
            id = "rumbaBox",
            meter = "4/4",
            bpm = 100,
            beatsPerBar = 4,
            loopBeats = 8,
            counts = "S · Q Q",
            startL = -0.08f to 0f,
            startR = 0.08f to 0f,
            steps =
                listOf(
                    Step(0f, "S", Foot.L, -0.08f, 0.35f),
                    Step(2f, "Q", Foot.R, 0.32f, 0.35f),
                    Step(3f, "Q", Foot.L, 0.16f, 0.35f),
                    Step(4f, "S", Foot.R, 0.32f, 0.00f),
                    Step(6f, "Q", Foot.L, -0.08f, 0.00f),
                    Step(7f, "Q", Foot.R, 0.08f, 0.00f),
                ),
            pauses = emptyList(),
            music = MusicStyle.RUMBA,
        )

    val salsaBasic =
        Pattern(
            id = "salsaBasic",
            meter = "4/4",
            bpm = 170,
            beatsPerBar = 4,
            loopBeats = 8,
            counts = "1 2 3 · 5 6 7 ·",
            startL = -0.08f to 0f,
            startR = 0.08f to 0f,
            steps =
                listOf(
                    Step(0f, "1", Foot.L, -0.08f, 0.30f),
                    Step(1f, "2", Foot.R, 0.08f, 0.00f),
                    Step(2f, "3", Foot.L, -0.08f, 0.00f),
                    Step(4f, "5", Foot.R, 0.08f, -0.30f),
                    Step(5f, "6", Foot.L, -0.08f, 0.00f),
                    Step(6f, "7", Foot.R, 0.08f, 0.00f),
                ),
            pauses = listOf(3, 7),
            music = MusicStyle.SALSA,
        )

    val chachaBasic =
        Pattern(
            id = "chachaBasic",
            meter = "4/4",
            bpm = 120,
            beatsPerBar = 4,
            loopBeats = 8,
            counts = "1 2 3 4&",
            startL = -0.08f to 0f,
            startR = 0.08f to 0f,
            steps =
                listOf(
                    Step(0f, "1", Foot.R, 0.08f, 0.00f),
                    Step(1f, "2", Foot.L, -0.08f, 0.30f),
                    Step(2f, "3", Foot.R, 0.08f, 0.00f),
                    Step(3f, "4", Foot.L, -0.08f, 0.00f),
                    Step(3.5f, "&", Foot.R, 0.08f, 0.00f),
                    Step(4f, "1", Foot.L, -0.08f, 0.00f),
                    Step(5f, "2", Foot.R, 0.08f, -0.30f),
                    Step(6f, "3", Foot.L, -0.08f, 0.00f),
                    Step(7f, "4", Foot.R, 0.08f, 0.00f),
                    Step(7.5f, "&", Foot.L, -0.08f, 0.00f),
                ),
            pauses = emptyList(),
            music = MusicStyle.CHACHA,
        )

    val all: List<Pattern> = listOf(waltzBox, rumbaBox, salsaBasic, chachaBasic)

    fun byId(id: String?): Pattern? = all.firstOrNull { it.id == id }
}
