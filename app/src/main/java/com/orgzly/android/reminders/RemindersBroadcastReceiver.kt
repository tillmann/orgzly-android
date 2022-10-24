package com.orgzly.android.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orgzly.BuildConfig
import com.orgzly.android.App
import com.orgzly.android.AppIntent
import com.orgzly.android.data.DataRepository
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.util.LogMajorEvents
import com.orgzly.android.util.LogUtils
import com.orgzly.android.util.async
import com.orgzly.org.datetime.OrgDateTime
import org.joda.time.DateTime
import javax.inject.Inject

class RemindersBroadcastReceiver : BroadcastReceiver() {
    @Inject
    lateinit var dataRepository: DataRepository

    override fun onReceive(context: Context, intent: Intent) {
        App.appComponent.inject(this)

        if (!anyRemindersEnabled(context, intent)) {
            return
        }

        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, intent, intent.action, intent.extras)

        async {
            val now = DateTime()
            val lastRun = LastRun.fromPreferences(context)

            RemindersScheduler.cancelAll(context)

            if (LogMajorEvents.isEnabled()) {
                LogMajorEvents.log(
                    LogMajorEvents.REMINDERS,
                    "Canceled all reminders (now:$now last-run:$lastRun"
                )
            }

            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                AppIntent.ACTION_REMINDER_DATA_CHANGED -> {
                    // Nothing to do, just schedule the next alert below
                }

                AppIntent.ACTION_REMINDER_TRIGGERED -> {
                    reminderTriggered(context, now, lastRun)
                }

                AppIntent.ACTION_REMINDER_SNOOZE_ENDED -> {
                    intent.extras?.apply {
                        val noteId: Long = getLong(AppIntent.EXTRA_NOTE_ID, 0)
                        val noteTimeType: Int = getInt(AppIntent.EXTRA_NOTE_TIME_TYPE, 0)
                        val timestamp: Long = getLong(AppIntent.EXTRA_SNOOZE_TIMESTAMP, 0)

                        if (noteId > 0) {
                            snoozeEnded(context, noteId, noteTimeType, timestamp)
                        }
                    }
                }
            }

            scheduleNextReminder(context, now, lastRun)

            LastRun.toPreferences(context, now)
        }
    }

    private fun anyRemindersEnabled(context: Context, intent: Intent): Boolean {
        return if (AppPreferences.remindersForScheduledEnabled(context)) {
            if (LogMajorEvents.isEnabled()) {
                LogMajorEvents.log(
                    LogMajorEvents.REMINDERS,
                    "Intent accepted - scheduled time reminder is enabled: $intent"
                )
            }
            true
        } else if (AppPreferences.remindersForDeadlineEnabled(context)) {
            if (LogMajorEvents.isEnabled()) {
                LogMajorEvents.log(
                    LogMajorEvents.REMINDERS,
                    "Intent accepted - deadline time reminder is enabled: $intent"
                )
            }
            true
        } else if (AppPreferences.remindersForEventsEnabled(context)) {
            if (LogMajorEvents.isEnabled()) {
                LogMajorEvents.log(
                    LogMajorEvents.REMINDERS,
                    "Intent accepted - events reminder is enabled: $intent"
                )
            }
            true
        } else {
            if (LogMajorEvents.isEnabled()) {
                LogMajorEvents.log(
                    LogMajorEvents.REMINDERS,
                    "Intent ignored - all reminders are disabled: $intent"
                )
            }
            false
        }
    }

    /**
     * Display reminders for all notes with times between previous run and now.
     */
    private fun reminderTriggered(context: Context, now: DateTime, lastRun: LastRun?) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        val msg = if (lastRun != null) {
            val notes = NoteReminders.getNoteReminders(
                context, dataRepository, now, lastRun, NoteReminders.INTERVAL_FROM_LAST_TO_NOW)

            if (notes.isNotEmpty()) {
                "Triggered: Found ${notes.size} notes between $lastRun and $now".also {
                    RemindersNotifications.showNotification(context, notes)
                }

            } else {
                "Triggered: No notes found between $lastRun and $now"
            }

        } else {
            "Triggered: No previous run"
        }

        if (LogMajorEvents.isEnabled()) {
            LogMajorEvents.log(LogMajorEvents.REMINDERS, msg)
        }

        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, msg)
    }

    /**
     * Schedule the next job for times after now.
     */
    private fun scheduleNextReminder(context: Context, now: DateTime, lastRun: LastRun) {
        val notes = NoteReminders.getNoteReminders(
            context, dataRepository, now, lastRun, NoteReminders.INTERVAL_FROM_NOW)

        if (notes.isNotEmpty()) {
            // Schedule only the first upcoming time
            val firstNote = notes.first()

            val id = firstNote.payload.noteId
            val title = firstNote.payload.title
            val runAt = firstNote.runTime.millis
            val hasTime = firstNote.payload.orgDateTime.hasTime()

            // Schedule in this many milliseconds
            var inMs = runAt - now.millis
            if (inMs < 0) {
                inMs = 1
            }

            RemindersScheduler.scheduleReminder(context, inMs, hasTime)

            if (LogMajorEvents.isEnabled()) {
                LogMajorEvents.log(
                    LogMajorEvents.REMINDERS,
                    "Next: Found ${notes.size} notes between $lastRun and $now and scheduled first in ${inMs / 1000} sec: \"$title\" (id:$id)"
                )
            }

        } else {
            if (LogMajorEvents.isEnabled()) {
                LogMajorEvents.log(
                    LogMajorEvents.REMINDERS, "Next: No notes found between $lastRun and $now"
                )
            }
        }
    }

    private fun snoozeEnded(context: Context, noteId: Long, noteTimeType: Int, timestamp: Long) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, noteId, timestamp)

        val reminders = mutableListOf<NoteReminder>()

        for (noteTime in dataRepository.times()) {
            if (noteTime.noteId == noteId
                && noteTime.timeType == noteTimeType
                && NoteReminders.isRelevantNoteTime(context, noteTime)) {

                val orgDateTime = OrgDateTime.parse(noteTime.orgTimestampString)

                val payload = NoteReminderPayload(
                    noteTime.noteId,
                    noteTime.bookId,
                    noteTime.bookName,
                    noteTime.title,
                    noteTime.timeType,
                    orgDateTime)

                val timestampDateTime = DateTime(timestamp)

                reminders.add(NoteReminder(timestampDateTime, payload))
            }
        }

        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Found ${reminders.size} notes")

        if (reminders.isNotEmpty()) {
            RemindersNotifications.showNotification(context, reminders)
        }
    }

    companion object {
        private val TAG: String = RemindersBroadcastReceiver::class.java.name
    }
}