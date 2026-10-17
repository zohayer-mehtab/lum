package lumen.app.data

import android.content.Context
import lumen.core.Filters

class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var aspectId: String
        get() = prefs.getString("aspectId", "4:3") ?: "4:3"
        set(value) = prefs.edit().putString("aspectId", value).apply()

    var timer: Int
        get() = prefs.getInt("timer", 0)
        set(value) = prefs.edit().putInt("timer", value).apply()

    var filterId: String
        get() = prefs.getString("filterId", Filters.NONE_ID) ?: Filters.NONE_ID
        set(value) = prefs.edit().putString("filterId", value).apply()

    var motion: Boolean
        get() = prefs.getBoolean("motion", false)
        set(value) = prefs.edit().putBoolean("motion", value).apply()
}