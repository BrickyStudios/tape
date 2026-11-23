package io.github.brickystudios.tape

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// A wire desk at night: graphite surfaces, one amber accent. Amber only marks what is minutes old.
val Ink = Color(0xFF121212)
val Raised = Color(0xFF1C1C1C)
val Text1 = Color(0xFFECEAE6)
val Text2 = Color(0xFFA19D97)
/** 4.6:1 on Ink, still readable for 13 sp meta */
val Text3 = Color(0xFF87837D)
val Amber = Color(0xFFF2A93B)
val Hairline = Text1.copy(alpha = 0.08f)

val AppType = Typography().run { copy(bodyLarge = bodyLarge.copy(letterSpacing = 0.sp)) }
val TitleStyle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = Text1)
val HeadlineStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = Text1)
val MetaStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Text2)
val ClockStyle = TextStyle(fontSize = 13.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
val SectionStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Text2)

/** Headlines this young get the amber clock. */
const val LIVE = 10 * MINUTE

fun open(ctx: Context, link: String) = runCatching {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** "18:41" today, "Mon" this week, "23 Nov" before. */
fun clock(ctx: Context, time: Long, now: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    return when {
        time >= day -> DateFormat.getTimeFormat(ctx).format(Date(time))
        time >= day - 6 * 24 * HOUR -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(time))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))
    }
}

@Composable
fun Title(text: String, sub: String, clock: Boolean = true) {
    Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
        Text(text, style = TitleStyle)
        Text(sub, style = if (clock) MetaStyle.copy(fontFamily = FontFamily.Monospace, color = Text3) else MetaStyle)
    }
}

/** Clock in its own column, headline, then the stocks it is about and where it comes from. */
@Composable
fun HeadlineRow(item: Item, now: Long) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { open(ctx, item.link) }.padding(vertical = 12.dp),
    ) {
        Text(
            clock(ctx, item.time, now),
            Modifier.width(72.dp),
            style = ClockStyle,
            color = if (now - item.time in 0 until LIVE) Amber else Text3,
            maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            Text(item.title, style = HeadlineStyle, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                buildAnnotatedString {
                    if (item.tickers.isNotEmpty()) {
                        withStyle(SpanStyle(color = Text1, fontFamily = FontFamily.Monospace)) { append(item.tickers.joinToString("  ")) }
                        append("  ·  ")
                    }
                    append(item.source)
                },
                style = MetaStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = Hairline)
}
