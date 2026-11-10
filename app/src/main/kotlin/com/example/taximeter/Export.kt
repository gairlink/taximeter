package com.example.taximeter

import android.content.Context
import android.widget.Toast
import java.io.File
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

fun periodLabel(p: Period): String = "${fmtDate(periodStart(p))} - ${fmtDate(System.currentTimeMillis())}"

private fun csvField(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

private fun num(d: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", d)

fun tripsCsv(list: List<Trip>): String = buildString {
    // FIX: extra column removed - Trip no longer has that field.
    // Before, this line referenced a deleted field and the project did not compile.
    appendLine("date,mode,km,minutes,fare_eur,fuel_eur,profit_eur")
    list.sortedBy { it.time }.forEach { t ->
        appendLine(
            listOf(
                csvField(fmtDateTime(t.time)),
                csvField(t.mode.label),
                num(t.km, 3),
                num(t.seconds / 60.0, 1),
                num(t.fare, 2),
                num(t.fuel, 2),
                num(t.profit, 2),
            ).joinToString(",")
        )
    }
}

fun expensesCsv(list: List<Expense>): String = buildString {
    appendLine("date,category,amount_eur,note")
    list.sortedBy { it.time }.forEach { ex ->
        appendLine(
            listOf(
                csvField(fmtDateTime(ex.time)),
                csvField(ex.category),
                num(ex.amount, 2),
                csvField(ex.note),
            ).joinToString(",")
        )
    }
}

private val saveCsvLock = Any()

fun saveCsv(context: Context, name: String, content: String) {
    // FIX: atomic + locked. Before, writeText() truncated the file directly -
    // double-tap / kill mid-save left a half-written CSV.
    synchronized(saveCsvLock) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, name)
            // FIX: write UTF-8 BOM (\uFEFF) so Excel opens Georgian text correctly.
            // Before, plain UTF-8 without BOM made Georgian mode/category text
            // show as garbage in Excel on Windows.
            val tmp = File(dir, "$name.tmp")
            tmp.writeText("\uFEFF" + content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            Toast.makeText(context, "შენახულია: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: java.io.IOException) {
            Toast.makeText(context, "CSV ვერ შეინახა", Toast.LENGTH_LONG).show()
        }
    }
}
