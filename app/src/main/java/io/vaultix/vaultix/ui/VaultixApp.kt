package io.vaultix.vaultix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 应用首页：选择要打开的密码库类型。
 * Vaultix 同时管理两类密码库（见 Docs/00）：
 *  - Bitwarden 云端同步库（full 分发）
 *  - KeePass KDBX 本地库（full / offline 分发）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultixApp() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Vaultix") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "选择要打开的密码库",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))
            VaultCard(
                title = "Bitwarden 同步库",
                description = "通过 Bitwarden API 同步云端密码库（需网络）"
            )
            Spacer(Modifier.height(12.dp))
            VaultCard(
                title = "KDBX 本地库",
                description = "离线 KeePass 格式本地文件，不依赖网络"
            )
        }
    }
}

@Composable
private fun VaultCard(title: String, description: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
