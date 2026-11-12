package com.example.taximeter

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun fmtDate(t: Long): String = SimpleDateFormat("d.MM.yyyy", Locale.US).format(Date(t))

fun fmtDateTime(t: Long): String = SimpleDateFormat("d.MM.yyyy, HH:mm:ss", Locale.US).format(Date(t))

/** Start of the selected period, in epoch millis. */
fun periodStart(p: Period): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    when (p) {
        Period.TODAY -> Unit
        Period.WEEK -> cal.add(Calendar.DAY_OF_YEAR, -6)
        Period.MONTH -> cal.set(Calendar.DAY_OF_MONTH, 1)
    }
    return cal.timeInMillis
}
