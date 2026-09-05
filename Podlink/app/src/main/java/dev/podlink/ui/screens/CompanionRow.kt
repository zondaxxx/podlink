package dev.podlink.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.podlink.R
import dev.podlink.service.CompanionLink
import dev.podlink.service.PodsRepo

/** "Pair as companion device" row shared by onboarding and settings. */
@Composable
fun CompanionRow(compact: Boolean = false) {
    val ctx = LocalContext.current
    if (!CompanionLink.available(ctx)) return
    var tick by remember { mutableStateOf(0) }
    val associated = remember(tick) { CompanionLink.associatedAddresses(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            CompanionLink.observePresence(ctx)
            Toast.makeText(ctx, R.string.companion_ok, Toast.LENGTH_SHORT).show()
        }
        tick++
    }
    fun start() {
        val svc = PodsRepo.service
        val bonded = svc?.monitor?.bondedPods().orEmpty()
        val target = bonded.firstOrNull { it.address.equals(PodsRepo.state.value.address, true) } ?: bonded.firstOrNull()
        CompanionLink.associate(
            ctx, target?.address, target?.name,
            onChooser = { launcher.launch(IntentSenderRequest.Builder(it).build()) },
            onError = { Toast.makeText(ctx, ctx.getString(R.string.companion_fail, it), Toast.LENGTH_LONG).show() },
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.companion_title), fontWeight = FontWeight.SemiBold)
            if (!compact) Text(stringResource(R.string.companion_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (associated.isNotEmpty()) {
            Icon(Icons.Rounded.CheckCircle, stringResource(R.string.granted), tint = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { CompanionLink.disassociateAll(ctx); tick++ }) { Text(stringResource(R.string.companion_remove)) }
        } else Button(onClick = { start() }) { Text(stringResource(R.string.companion_pair)) }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
