package com.bastion.app.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.BottomNavContentTab

// dragHandleModifier 是「拖拽手柄」自己的 Modifier，与整行的 modifier 语义不同，
// 无法也不该改名为 modifier，故抑制 lint 的「Modifier 参数必须叫 modifier」告警。
@SuppressLint("ModifierParameter")
@Composable
internal fun BottomNavConfigRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    switchEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true,
    dragHandleModifier: Modifier = Modifier
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showDragHandle) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = switchEnabled
            )
        }
    }
}

internal fun BottomNavContentTab.toIcon(): ImageVector = when (this) {
    BottomNavContentTab.VAULT_V2 -> Icons.Default.Home
    BottomNavContentTab.PASSWORDS -> Icons.Default.Lock
    BottomNavContentTab.AUTHENTICATOR -> Icons.Default.Security
    BottomNavContentTab.CARD_WALLET -> Icons.Default.Wallet
    BottomNavContentTab.GENERATOR -> Icons.Default.AutoAwesome
    BottomNavContentTab.NOTES -> Icons.Default.Note
    BottomNavContentTab.PASSKEY -> Icons.Default.Key
    BottomNavContentTab.SEND -> Icons.AutoMirrored.Default.Send
}

internal fun BottomNavContentTab.toLabelRes(): Int = when (this) {
    BottomNavContentTab.VAULT_V2 -> R.string.nav_v2_vault
    BottomNavContentTab.PASSWORDS -> R.string.nav_passwords
    BottomNavContentTab.AUTHENTICATOR -> R.string.nav_authenticator
    BottomNavContentTab.CARD_WALLET -> R.string.nav_card_wallet
    BottomNavContentTab.GENERATOR -> R.string.nav_generator
    BottomNavContentTab.NOTES -> R.string.nav_notes
    BottomNavContentTab.PASSKEY -> R.string.nav_passkey
    BottomNavContentTab.SEND -> R.string.nav_v2_send
}
