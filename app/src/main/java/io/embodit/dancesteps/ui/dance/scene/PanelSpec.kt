package io.embodit.dancesteps.ui.dance.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val MIRROR = "mirror"
    const val MENU = "moreMenu"
    const val SNACK = "snackbar"
    const val DIALOG = "blockedDialog"
    const val COACH = "coach"

    /** Width / height in dp. The mirror is 640 x 520 at scale 1 (1 dp = 0.8 mm: 0.512 x 0.416 m). */
    val MIRROR_SIZE = 640f to 520f
    val MENU_SIZE = 288f to 296f
    val SNACK_SIZE = 520f to 72f
    val DIALOG_SIZE = 480f to 280f
    val COACH_SIZE = 520f to 136f

    const val MIRROR_SCALE = 1f

    /** The dialog stands 0.9 m ahead at 1.125. */
    const val DIALOG_SCALE = 1.125f
    const val DIALOG_DISTANCE_M = 0.9f

    val pool: List<String> = listOf(MIRROR, MENU, SNACK, DIALOG, COACH)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            MIRROR -> MIRROR_SIZE
            MENU -> MENU_SIZE
            SNACK -> SNACK_SIZE
            DIALOG -> DIALOG_SIZE
            else -> COACH_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
