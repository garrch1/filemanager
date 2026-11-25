package com.example.fm

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SortBy(val label: String) { NAME("Имя"), DATE("Дата"), SIZE("Размер"), TYPE("Тип") }

object Prefs {
    private var sp: SharedPreferences? = null
    var theme by mutableStateOf(0)          // 0 системная, 1 светлая, 2 тёмная
    var showHidden by mutableStateOf(false)
    var thumbnails by mutableStateOf(true)
    var useTrash by mutableStateOf(true)
    var trashDays by mutableStateOf(30)     // 0 = не очищать автоматически
    var sortBy by mutableStateOf(SortBy.NAME)
    var sortDesc by mutableStateOf(false)

    fun init(c: Context) {
        val s = c.getSharedPreferences("fm", Context.MODE_PRIVATE); sp = s
        theme = s.getInt("theme", 0)
        showHidden = s.getBoolean("hidden", false)
        thumbnails = s.getBoolean("thumbs", true)
        useTrash = s.getBoolean("trash", true)
        trashDays = s.getInt("trashDays", 30)
        sortBy = SortBy.values().getOrElse(s.getInt("sortBy", 0)) { SortBy.NAME }
        sortDesc = s.getBoolean("desc", false)
    }

    fun save() {
        sp?.edit()?.putInt("theme", theme)?.putBoolean("hidden", showHidden)?.putBoolean("thumbs", thumbnails)
            ?.putBoolean("trash", useTrash)?.putInt("trashDays", trashDays)?.putInt("sortBy", sortBy.ordinal)
            ?.putBoolean("desc", sortDesc)?.apply()
    }
}
