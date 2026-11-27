package org.jellyfin.mobile.ui.utils

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** The quiet zone around the code, in modules (the standard asks for 4). */
private const val QUIET_ZONE = 4

/** The QR code of [text], dark on white with its quiet zone, filling the size it is given (make it square). */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { QrCode.encode(text) }
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val count = modules.size + QUIET_ZONE * 2
        val unit = minOf(size.width, size.height) / count
        drawRect(Color.White, size = Size(unit * count, unit * count))
        for (y in modules.indices) {
            for (x in modules[y].indices) {
                if (modules[y][x]) {
                    // A hair larger, so neighbouring modules leave no seams
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + QUIET_ZONE) * unit, (y + QUIET_ZONE) * unit),
                        size = Size(unit + 0.5f, unit + 0.5f),
                    )
                }
            }
        }
    }
}
