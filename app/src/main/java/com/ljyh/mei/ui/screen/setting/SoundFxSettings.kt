package com.ljyh.mei.ui.screen.setting

import android.content.Intent
import android.media.Spatializer
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ljyh.mei.constants.EqualizerBandGainsKey
import com.ljyh.mei.constants.EqualizerPreampKey
import com.ljyh.mei.constants.EqualizerPresetKey
import com.ljyh.mei.constants.SoundFxBassKey
import com.ljyh.mei.constants.SoundFxEnabledKey
import com.ljyh.mei.constants.SoundFxPresetKey
import com.ljyh.mei.constants.SoundFxSurroundKey
import com.ljyh.mei.playback.EqualizerPreset
import com.ljyh.mei.playback.SoundFxPreset
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.scaffold.glasmioHorizontalContentPadding
import io.github.neoruaa.glasmio.scaffold.glasmioHorizontalEdgeToEdge
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioSlider

@Composable
fun SoundFxSettings() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (enabled, setEnabled) = rememberPreference(SoundFxEnabledKey, false)
    val (surround, setSurround) = rememberPreference(SoundFxSurroundKey, 0)
    val (bass, setBass) = rememberPreference(SoundFxBassKey, 0)
    val (fxPreset, setFxPreset) = rememberEnumPreference(SoundFxPresetKey, SoundFxPreset.Custom)
    // 预设组合会同时写入 EQ（组合预设 = EQ 曲线 + 低音 + 环绕）
    val (_, setEqPreset) = rememberEnumPreference(EqualizerPresetKey, EqualizerPreset.Flat)
    val (_, setEqPreamp) = rememberPreference(EqualizerPreampKey, EqualizerPreset.Flat.preamp)
    val (_, setSerializedGains) = rememberPreference(
        EqualizerBandGainsKey,
        EqualizerPreset.Flat.gains.joinToString(","),
    )

    fun applyFxPreset(next: SoundFxPreset) {
        if (next == SoundFxPreset.Custom) return
        setFxPreset(next)
        setEnabled(true)
        setSurround(next.surround)
        setBass(next.bass)
        setEqPreset(next.equalizerPreset)
        setEqPreamp(next.equalizerPreset.preamp)
        setSerializedGains(next.equalizerPreset.gains.joinToString(","))
    }

    fun editSlider() {
        setFxPreset(SoundFxPreset.Custom)
    }

    // 系统空间音频（Android 13+ Spatializer）：检测设备支持与开关状态
    val spatial = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val spatializer = context.getSystemService(Spatializer::class.java) ?: return@runCatching null
                SpatialInfo(available = spatializer.isAvailable, enabled = spatializer.isEnabled)
            }.getOrNull()
        } else null
    }

    MeiloXPinnedListPage(
        title = "音效",
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        actions = {
            listOf(meiloxHeaderAction(
                key = "soundfx.reset",
                contentDescription = "重置音效",
                onClick = {
                    setEnabled(false)
                    setSurround(0)
                    setBass(0)
                    setFxPreset(SoundFxPreset.Custom)
                },
            ) { Text("重置") })
        },
    ) {
        item {
            SettingsCard(Modifier.fillMaxWidth(), onClick = { setEnabled(!enabled) }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text("启用音效")
                        Text(
                            "环绕声与重低音，跟随播放管线实时生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MeiloXSwitch(enabled, setEnabled)
                }
            }
        }
        item {
            Text(
                "预设组合（含均衡器曲线）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth().glasmioHorizontalEdgeToEdge().swipeBackExcluded(),
                contentPadding = glasmioHorizontalContentPadding(PaddingValues(horizontal = 0.dp)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SoundFxPreset.entries, key = SoundFxPreset::name) { item ->
                    GlasmioButton(
                        onClick = { applyFxPreset(item) },
                        emphasis = if (item == fxPreset && item != SoundFxPreset.Custom) GlassEmphasis.Prominent else GlassEmphasis.Regular,
                    ) {
                        Text(item.label)
                    }
                }
            }
        }
        item {
            SettingsGroupedList {
                SliderRow(
                    title = "环绕声宽化",
                    detail = "立体声侧信号增强，人声居中、声场外扩",
                    value = surround, onValueChange = { setSurround(it); editSlider() },
                )
                SliderRow(
                    title = "重低音",
                    detail = "90Hz 低频增强",
                    value = bass, onValueChange = { setBass(it); editSlider() },
                )
            }
        }
        item {
            SettingsCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("系统空间音频")
                    Text(
                        when {
                            spatial == null -> "此设备不支持（需要 Android 13+）"
                            !spatial.available -> "设备或耳机不支持系统全景声"
                            !spatial.enabled -> "设备支持，当前未开启"
                            else -> "已开启，正在使用系统全景声"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (spatial != null && spatial.available && !spatial.enabled) {
                        GlasmioButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("去系统设置开启") }
                    }
                }
            }
        }
    }
}

private data class SpatialInfo(val available: Boolean, val enabled: Boolean)

@Composable
private fun SliderRow(
    title: String,
    detail: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                Text("$value", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlasmioSlider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                modifier = Modifier.fillMaxWidth(),
                valueRange = 0f..100f,
            )
        }
    }
}
