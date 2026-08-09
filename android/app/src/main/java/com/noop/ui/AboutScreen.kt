package com.noop.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.BuildConfig
import com.noop.R

// MARK: - About (minimal) — reached from the More menu
//
// A deliberately minimal About: the app name, the build version, and a link to the project's source on
// GitHub. Everything else that used to live in Settings → About (What's new, scoring guide, support,
// attributions) is out of scope here.

private const val GITHUB_URL = "https://github.com/tanarchytan/noop"
private const val GITHUB_LABEL = "github.com/tanarchytan/noop"

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    ScreenScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.about_subtitle),
    ) {
        NoopCard(padding = 20.dp, tint = Palette.accent) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                ) {
                    Text(stringResource(R.string.app_name), style = NoopType.title2, color = Palette.textPrimary)
                    StatePill("v${BuildConfig.VERSION_NAME}", tone = StrandTone.Neutral, showsDot = false)
                }

                // Project home — NOOP's code, releases and issues live on GitHub.
                val projectHomeInteraction = remember { MutableInteractionSource() }
                val sourceLabel = stringResource(R.string.about_source_github_a11y, GITHUB_LABEL)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidPress(projectHomeInteraction)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.accent.copy(alpha = 0.10f))
                        .border(Metrics.divider, Palette.accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = projectHomeInteraction,
                            indication = null,
                        ) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, GITHUB_LABEL, Toast.LENGTH_LONG).show()
                            }
                        }
                        .padding(horizontal = Metrics.space14, vertical = 12.dp)
                        .semantics { contentDescription = sourceLabel },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                        Text(stringResource(R.string.about_source_github), style = NoopType.body, color = Palette.textPrimary)
                        Text(GITHUB_LABEL, style = NoopType.caption, color = Palette.textTertiary)
                    }
                }
            }
        }
    }
}
