package lumen.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

val Ink = Color(0xFF000000)
val Panel = Color(0xFF17171A)
val PanelHi = Color(0xFF2A2A2F)
val Accent = Color(0xFFFFD60A)
val Danger = Color(0xFFFF5A52)
val Good = Color(0xFF4CD964)
val Dim = Color(0xFF9A9AA2)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.Black, background = Ink, surface = Panel,
            onSurface = Color.White, onBackground = Color.White, error = Danger,
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Ink, contentColor = Color.White) { content() }
    }
}

/** Selectable rounded chip. */
@Composable
fun Pill(text: String, selected: Boolean = false, enabled: Boolean = true, tint: Color = Accent, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) tint else PanelHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = when { selected -> Color.Black; enabled -> Color.White; else -> Dim },
        )
    }
}

/** Rectangular action button. */
@Composable
fun Btn(text: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (primary && enabled) Accent else PanelHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = when { primary && enabled -> Color.Black; enabled -> Color.White; else -> Dim },
        )
    }
}

/** Sharing helpers. All write only into cache/exports (FileProvider-exposed). */
object Share {
    fun json(ctx: Context, json: String, name: String) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name).apply { writeText(json) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Export $name"))
    }

    fun text(ctx: Context, text: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(Intent.createChooser(i, title))
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lumen", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}
