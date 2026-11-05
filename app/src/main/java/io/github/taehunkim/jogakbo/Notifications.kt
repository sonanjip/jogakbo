package io.github.taehunkim.jogakbo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID

object Notifications {
    /** Background work on notes (fetching, transcribing, summarizing, embedding). */
    const val CH_JOBS = "jobs"
    /** A recording is running. */
    const val CH_RECORDING = "recording"
    /** Another app asks for access to the notes. */
    const val CH_APPS = "apps"
    const val ID_RECORDING = 1001
    const val ID_EMBED = 1002
    const val ID_APPROVAL = 1003
    private const val ID_JOB_BASE = 10000
    const val EXTRA_NOTE = "noteId"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_JOBS, ctx.getString(R.string.channel_jobs), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RECORDING, ctx.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_APPS, ctx.getString(R.string.channel_apps), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun jobId(noteId: Long) = ID_JOB_BASE + (noteId % 50_000).toInt()

    /** Opens the app on [noteId] (or the list when null). */
    fun openNote(ctx: Context, noteId: Long?): PendingIntent = PendingIntent.getActivity(
        ctx, noteId?.toInt() ?: 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { noteId?.let { putExtra(EXTRA_NOTE, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, workId: UUID, title: String, text: String?, done: Int, total: Int, noteId: Long?): Notification {
        ensureChannels(ctx)
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(ctx, CH_JOBS)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openNote(ctx, noteId))
            .setProgress(total, done, total == 0)
            .addAction(0, ctx.getString(R.string.action_stop), cancel)
            .build()
    }

    fun recording(ctx: Context, noteId: Long, text: String, stop: PendingIntent): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(ctx.getString(R.string.recording_notification))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openNote(ctx, noteId))
            .addAction(0, ctx.getString(R.string.action_stop), stop)
            .build()
    }

    fun approvalRequest(ctx: Context, label: String) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_APPROVE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_APPS)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(ctx.getString(R.string.approval_notification_title))
            .setContentText(ctx.getString(R.string.approval_notification_text, label))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notify(ctx, ID_APPROVAL, n)
    }

    fun foregroundInfo(id: Int, n: Notification) = ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun notify(ctx: Context, id: Int, n: Notification) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
