package com.debasish.livefit.phone.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.debasish.livefit.phone.LiveFitHubService
import com.debasish.livefit.phone.ui.theme.LiveFitTheme

/**
 * Spec §4: one dialog-style screen in front of notification access and the location prompt, used by Setup, Linked
 * music and the Permissions list, so no route skips the disclosure. "Not now" changes nothing.
 */
class DisclosureActivity : ComponentActivity() {
    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LiveFitHubService.promoteLocation(this) // granted: the visible app re-promotes the hub with `location`
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { n -> DisclosureKind.entries.firstOrNull { it.name == n } } ?: return finish()
        setContent {
            LiveFitTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(kind.title) },
                    text = { Text(kind.body, modifier = Modifier.verticalScroll(rememberScrollState())) },
                    confirmButton = { TextButton(onClick = { proceed(kind) }) { Text(kind.continueLabel) } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Not now") } },
                )
            }
        }
    }

    private fun proceed(kind: DisclosureKind) {
        when (kind) {
            DisclosureKind.Music -> {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                finish()
            }
            DisclosureKind.Location -> locationRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"

        fun intent(context: Context, kind: DisclosureKind): Intent =
            Intent(context, DisclosureActivity::class.java).putExtra(EXTRA_KIND, kind.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
