package uk.jj.grab

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Everything the Settings tab can change, plus a few remembered values. Stored on the device only.
 * Each setting is a flow, so screens that show it update the moment it changes.
 */
object Prefs {
    private var sp: SharedPreferences? = null
    private var positions: SharedPreferences? = null

    class Bool internal constructor(private val key: String, def: Boolean) {
        val flow = MutableStateFlow(def)
        var value: Boolean
            get() = flow.value
            set(v) { flow.value = v; sp?.edit()?.putBoolean(key, v)?.apply() }
        internal fun load(s: SharedPreferences) { flow.value = s.getBoolean(key, flow.value) }
    }

    class Num internal constructor(private val key: String, def: Int) {
        val flow = MutableStateFlow(def)
        var value: Int
            get() = flow.value
            set(v) { flow.value = v; sp?.edit()?.putInt(key, v)?.apply() }
        internal fun load(s: SharedPreferences) { flow.value = s.getInt(key, flow.value) }
    }

    // ---- downloads ----
    val parallel = Num("parallel", 2)                      // downloads running at once, 1 to 3
    val wifiOnly = Bool("wifi_only", false)
    val askQuality = Bool("ask_quality", true)             // off = always take best quality without asking
    val privateByDefault = Bool("private_default", false)  // tick "Save privately" by default
    val subtitles = Bool("subtitles", true)                // save subtitles inside the video when the site has them
    val coverArt = Bool("cover_art", true)                 // cover picture and title/artist on audio files
    val offerCopied = Bool("offer_copied", true)           // offer to download a link copied in another app

    // ---- browser ----
    val startPage = Num("start_page", START_HOME)
    val adBlock = Bool("ad_block", true)
    val skipYouTubeAds = Bool("skip_youtube_ads", true)

    // ---- look ----
    val theme = Num("theme", THEME_FOLLOW)
    val buttonColour = Num("button_colour", BUTTON_RED)
    /** Bumped when the Download button's saved position is reset, so the button moves back at once. */
    val buttonReset = MutableStateFlow(0)

    const val START_HOME = 0
    const val START_GOOGLE = 1
    const val START_LAST = 2
    const val THEME_FOLLOW = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2
    const val BUTTON_RED = 0
    const val BUTTON_GREEN = 1
    const val BUTTON_THEME = 2

    private val bools = listOf(wifiOnly, askQuality, privateByDefault, subtitles, coverArt, offerCopied, adBlock, skipYouTubeAds)
    private val nums = listOf(parallel, startPage, theme, buttonColour)

    fun init(ctx: Context) {
        val s = ctx.getSharedPreferences("grab", Context.MODE_PRIVATE)
        sp = s
        positions = ctx.getSharedPreferences("positions", Context.MODE_PRIVATE)
        bools.forEach { it.load(s) }
        nums.forEach { it.load(s) }
    }

    // ---- remembered values (not shown as settings) ----

    /** When the download engine last checked for an update (epoch millis). */
    var lastEngineCheck: Long
        get() = sp?.getLong("engine_check", 0L) ?: 0L
        set(v) { sp?.edit()?.putLong("engine_check", v)?.apply() }

    /** Sites (base domains) where the user switched ad and pop-up blocking off. */
    var adblockOff: Set<String>
        get() = sp?.getStringSet("adblock_off", emptySet())?.toSet() ?: emptySet()
        set(v) { sp?.edit()?.putStringSet("adblock_off", v)?.apply() }

    /** The page that was open last, for "open on the last page". */
    var lastUrl: String
        get() = sp?.getString("last_url", "") ?: ""
        set(v) { sp?.edit()?.putString("last_url", v)?.apply() }

    /** The quality picked last time, highlighted on the next sheet: a resolution (0 = highest) or an audio format. */
    var lastRes: Int
        get() = sp?.getInt("last_res", 0) ?: 0
        set(v) { sp?.edit()?.putInt("last_res", v)?.apply() }
    var lastAudio: String
        get() = sp?.getString("last_audio", "") ?: ""
        set(v) { sp?.edit()?.putString("last_audio", v)?.apply() }

    /** Timestamp of the clipboard entry Grab last looked at, so the same copied link is offered only once. */
    var lastClipStamp: Long
        get() = sp?.getLong("clip_stamp", 0L) ?: 0L
        set(v) { sp?.edit()?.putLong("clip_stamp", v)?.apply() }

    /** Where the Download button was left, as a fraction of the page area (below 0 = not moved yet). */
    var buttonX: Float
        get() = sp?.getFloat("button_x", -1f) ?: -1f
        set(v) { sp?.edit()?.putFloat("button_x", v)?.apply() }
    var buttonY: Float
        get() = sp?.getFloat("button_y", -1f) ?: -1f
        set(v) { sp?.edit()?.putFloat("button_y", v)?.apply() }

    fun resetButton() {
        buttonX = -1f
        buttonY = -1f
        buttonReset.value++
    }

    /** Where playback of a file stopped, so the built-in player can carry on from there. */
    fun playPosition(file: String): Long = positions?.getLong(file, 0L) ?: 0L

    fun setPlayPosition(file: String, ms: Long) {
        val e = positions?.edit() ?: return
        if (ms > 0) e.putLong(file, ms) else e.remove(file)
        e.apply()
    }
}
