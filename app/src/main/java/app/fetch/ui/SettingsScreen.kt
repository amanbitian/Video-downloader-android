package app.fetch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fetch.BuildConfig
import app.fetch.settings.AppSettings
import app.fetch.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClearBrowsingData: () -> Unit) {
    val settings by AppSettings.values.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) })

        SectionHeader("Downloads")
        SettingBlock("Simultaneous downloads", "Applies to downloads started after the current ones finish") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                (1..AppSettings.MAX_CONCURRENT).forEach { count ->
                    SegmentedButton(
                        selected = settings.maxConcurrentDownloads == count,
                        onClick = { AppSettings.update { it.copy(maxConcurrentDownloads = count) } },
                        shape = SegmentedButtonDefaults.itemShape(count - 1, AppSettings.MAX_CONCURRENT),
                    ) { Text("$count") }
                }
            }
        }
        SwitchLine("Download over Wi-Fi only", "Pause instead of using mobile data", settings.wifiOnly) { checked ->
            AppSettings.update { it.copy(wifiOnly = checked) }
        }
        InfoLine("Save location", "Videos → Movies/Fetch · Audio → Music/Fetch · Images → Pictures/Fetch · Other → Download/Fetch")

        SectionHeader("Appearance")
        SettingBlock("Theme", null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.theme == mode,
                        onClick = { AppSettings.update { it.copy(theme = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    ) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercaseChar)) }
                }
            }
        }

        SectionHeader("Privacy")
        InfoLine(
            "Clear browsing data",
            if (cleared) "Cookies, cache and history were cleared" else "Cookies, site storage, cache and history",
            onClick = { confirmClear = true }
        )

        SectionHeader("About")
        InfoLine("Version", BuildConfig.VERSION_NAME)
        Text(
            "Fetch only downloads direct, non-DRM media. YouTube is not supported. Respect content rights and each website's terms.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear browsing data?") },
            text = { Text("You'll be signed out of websites. Downloads and bookmarks are kept.") },
            confirmButton = { TextButton(onClick = { onClearBrowsingData(); cleared = true; confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SettingBlock(title: String, summary: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        if (summary != null) Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SwitchLine(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun InfoLine(title: String, summary: String, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
