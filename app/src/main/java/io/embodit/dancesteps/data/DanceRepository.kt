package io.embodit.dancesteps.data

import android.content.Context
import io.embodit.dancesteps.domain.model.LanguagePref
import io.embodit.dancesteps.domain.model.LastResult
import io.embodit.dancesteps.domain.model.Role
import io.embodit.dancesteps.domain.model.Settings
import io.embodit.dancesteps.domain.model.Speed
import io.embodit.dancesteps.domain.model.Verdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the app remembers between launches (design `persistence`): last pattern, role, the speed of each
 * pattern, language, whether the coachmark was seen, and the last verdict per pattern and speed. The
 * dance floor's place is not stored.
 */
interface DanceRepository {
    suspend fun load(): Settings

    suspend fun save(settings: Settings)
}

/** SharedPreferences-backed store; one JSON document, written on the IO dispatcher. */
class PrefsDanceRepository(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : DanceRepository {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun load(): Settings =
        withContext(io) {
            val text = prefs.getString(KEY, null) ?: return@withContext Settings()
            runCatching { SettingsJson.decode(text) }.getOrDefault(Settings())
        }

    override suspend fun save(settings: Settings) {
        withContext(io) { prefs.edit().putString(KEY, SettingsJson.encode(settings)).commit() }
    }

    private companion object {
        const val FILE = "dance_steps"
        const val KEY = "settings_v1"
    }
}

object SettingsJson {
    fun encode(s: Settings): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("lastPattern", s.lastPatternId ?: JSONObject.NULL)
        root.put("role", s.role.tag)
        root.put("speeds", JSONObject().apply { for ((k, v) in s.speeds) put(k, v.tag) })
        root.put("language", s.language.tag)
        root.put("coachSeen", s.coachSeen)
        root.put(
            "results",
            JSONObject().apply {
                for ((k, r) in s.results) put(k, JSONObject().put("verdict", r.verdict.tag).put("dev", r.devCm).put("loops", r.loops))
            },
        )
        return root.toString()
    }

    fun decode(text: String): Settings {
        val root = JSONObject(text)
        val speeds = HashMap<String, Speed>()
        root.optJSONObject("speeds")?.let { o -> for (k in o.keys()) speeds[k] = Speed.fromTag(o.optString(k)) }
        val results = HashMap<String, LastResult>()
        root.optJSONObject("results")?.let { o ->
            for (k in o.keys()) {
                val r = o.optJSONObject(k) ?: continue
                val verdict = Verdict.fromTag(r.optString("verdict")) ?: continue
                results[k] = LastResult(verdict, r.optInt("dev"), r.optInt("loops"))
            }
        }
        return Settings(
            lastPatternId = if (root.isNull("lastPattern")) null else root.optString("lastPattern"),
            role = Role.fromTag(root.optString("role")),
            speeds = speeds,
            language = LanguagePref.fromTag(root.optString("language", "system")),
            coachSeen = root.optBoolean("coachSeen", false),
            results = results,
        )
    }
}
