/*
 * Copyright (c) 2025 Nishant Mishra
 *
 * This file is part of Tomato - a minimalist pomodoro timer for Android.
 *
 * Tomato is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tomato is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tomato.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package org.nsh07.pomodoro.utils

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.nsh07.pomodoro.R
import org.nsh07.pomodoro.data.Session
import org.nsh07.pomodoro.data.SessionType
import java.util.TimeZone

class CalendarSyncManager(private val context: Context) {

    fun addSessionToCalendar(session: Session, calendarId: Long? = null) {
        val targetCalendarId = calendarId ?: getPrimaryCalendarId() ?: return

        val title = session.title ?: if (session.type == SessionType.FOCUS) {
            context.getString(R.string.focus)
        } else {
            context.getString(R.string.break_)
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, session.startTime)
            put(CalendarContract.Events.DTEND, session.endTime)
            put(CalendarContract.Events.TITLE, "Tomato: $title")
            put(CalendarContract.Events.CALENDAR_ID, targetCalendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.DESCRIPTION, "Pomodoro Session tracked by Tomato")
        }

        try {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )?.use { cursor ->
                var primaryId: Long? = null
                var firstId: Long? = null
                
                val idColumn = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryColumn = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val isPrimary = if (primaryColumn != -1) cursor.getInt(primaryColumn) == 1 else false
                    
                    if (firstId == null) firstId = id
                    if (isPrimary) {
                        primaryId = id
                        break
                    }
                }
                primaryId ?: firstId
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
