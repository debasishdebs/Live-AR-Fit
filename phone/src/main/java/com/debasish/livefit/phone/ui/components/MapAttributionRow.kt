package com.debasish.livefit.phone.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.debasish.livefit.map.MapAttribution
import com.debasish.livefit.phone.R
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** Spec §5: logo + text with tappable copyright links under a phone map display. */
@Composable
fun MapAttributionRow(attribution: MapAttribution, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (attribution.mapTilerLogo) {
                // The logo's wordmark is white (made for dark maps): tint it to ink so it reads on the light theme.
                Image(painterResource(R.drawable.maptiler_logo), contentDescription = "MapTiler", modifier = Modifier.height(16.dp), colorFilter = ColorFilter.tint(LiveFitColors.Ink))
                Spacer(Modifier.width(8.dp))
            }
            Text(attribution.text, style = MaterialTheme.typography.labelSmall, color = LiveFitColors.InkSoft)
        }
        Row {
            attribution.links.forEach { link ->
                TextButton(onClick = { runCatching { uri.openUri(link.url) } }) { Text("© ${link.label}", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
