package com.sidsaxena.notes.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** iOS-Notes-style relative/short date: "3:41 PM" today, "Tue" this week, "3/2/26" otherwise. */
fun formatNoteDate(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)

    return when {
        date.isEqual(today) ->
            Instant.ofEpochMilli(epochMillis).atZone(zone)
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        date.isAfter(today.minusDays(6)) ->
            date.format(DateTimeFormatter.ofPattern("EEE"))
        else ->
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    }
}
