package io.github.brickystudios.tape

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Date

object Notify {
    private const val STOCKS = "stocks"
    private const val WIRE = "wire"
    private const val WIRE_ID = 1
    const val EXTRA_TAB = "tab"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STOCKS, ctx.getString(R.string.channel_stocks), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(WIRE, ctx.getString(R.string.channel_wire), NotificationManager.IMPORTANCE_LOW))
    }

    /** One notification per headline about the watchlist; tapping it opens the article. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled is false without POST_NOTIFICATIONS
    fun stocks(ctx: Context, items: List<Item>) {
        val nm = NotificationManagerCompat.from(ctx)
        if (items.isEmpty() || !nm.areNotificationsEnabled()) return
        channels(ctx)
        // shortcut: at most 5 per check, the rest waits in the app; upgrade to a summary if people miss them
        for (it in items.take(5)) {
            nm.notify(
                it.id.hashCode(),
                base(ctx, STOCKS, it.time, Intent(Intent.ACTION_VIEW, Uri.parse(it.link)), it.id.hashCode())
                    .setContentTitle(it.tickers.joinToString("  ") + "  ·  " + it.source)
                    .setContentText(it.title)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(it.title))
                    .setGroup(STOCKS)
                    .build(),
            )
        }
    }

    /** One quiet notification that always shows the newest wire headlines. */
    @SuppressLint("MissingPermission")
    fun wire(ctx: Context, latest: List<Item>) {
        val nm = NotificationManagerCompat.from(ctx)
        if (latest.isEmpty() || !nm.areNotificationsEnabled()) return
        channels(ctx)
        val time = DateFormat.getTimeFormat(ctx)
        val lines = NotificationCompat.InboxStyle()
        latest.forEach { lines.addLine(time.format(Date(it.time)) + "  " + it.title) }
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TAB, 1).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        nm.notify(
            WIRE_ID,
            base(ctx, WIRE, latest[0].time, open, WIRE_ID)
                .setContentTitle(latest[0].title)
                .setContentText(latest[0].source)
                .setStyle(lines)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun base(ctx: Context, channel: String, time: Long, intent: Intent, code: Int) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFF2A93B.toInt())
            .setWhen(time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
}
