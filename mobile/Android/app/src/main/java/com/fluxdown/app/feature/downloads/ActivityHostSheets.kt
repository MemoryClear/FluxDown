package com.fluxdown.app.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.i18n.fill
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.core.format.Format
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxKeyValue
import com.fluxdown.fluxui.controls.FluxListRow
import com.fluxdown.fluxui.controls.FluxPresenceDot
import com.fluxdown.fluxui.controls.FluxStatRow
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.StatItem
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.data.Waveform
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxSheet
import com.fluxdown.fluxui.overlay.FluxSheetDetent
import com.fluxdown.fluxui.overlay.FluxSheetHeader
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme

// ── 主机切换 ─────────────────────────────────────────────────────────────────

/**
 * 主机切换器。目前只有当前主机（演示主机 / 之后的本机），没有可切换的远程主机：
 * 不放假主机，也不放无效的「添加主机」按钮（远程主机需要引擎桥接，见集成说明）。
 */
@Composable
fun HostSwitchSheet(visible: Boolean, onDismiss: () -> Unit) {
    val title = stringResource(R.string.mobileHostSwitchTitle)
    val sub = stringResource(R.string.mobileHostSwitchSub)
    FluxSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        detent = FluxSheetDetent.Wrap,
        title = title,
        header = { FluxSheetHeader(title = title, subtitle = sub, onClose = onDismiss) },
    ) {
        HostSwitchBody(onDismiss)
    }
}

@Composable
private fun ColumnScope.HostSwitchBody(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val view = LocalDownloadsView.current
    val c = FluxTheme.colors
    val host by container.host.collectAsState()
    val online = view.live
    val name = host.title()
    val sub = host.subtitle()
    val subtitle = if (online || sub.isEmpty()) sub else stringResource(R.string.mobileHostOfflineSubtitle).fill("subtitle" to sub)
    val state = stringResource(if (online) R.string.mobileHostOnline else R.string.mobileHostOffline)
    val current = stringResource(R.string.mobileHostCurrent)

    GlassSection(footer = stringResource(R.string.mobileHostListFootnote)) {
        row(hasIcon = true) {
            FluxListRow(
                title = name,
                subtitle = subtitle.ifEmpty { null },
                leading = {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.glass2),
                        contentAlignment = Alignment.Center,
                    ) { FluxIcon(FluxIcons.Smartphone, null, size = 20.dp, tint = c.inkMuted) }
                },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FluxPresenceDot(tone = if (online) Tone.Mint else Tone.Neutral, glow = online)
                        FluxIcon(FluxIcons.Check, current, size = 20.dp, tint = c.accentHi)
                    }
                },
                selected = true,
                onClick = onDismiss,
                modifier = Modifier.semantics { stateDescription = state },
            )
        }
    }
}

// ── 活动面板 D9 ──────────────────────────────────────────────────────────────

/**
 * D9 活动面板：大读数、上下行波形（60 s）、活跃 / 排队 / 重试、剩余空间、全部暂停 / 恢复。
 * 下载 / 上传限速编辑不在会话端口里（无 patch 之外的限速命令），这里不提供。
 */
@Composable
fun ActivitySheet(visible: Boolean, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val host by container.host.collectAsState()
    val title = stringResource(R.string.mobileInstrumentLabel)
    val hostName = host.title()
    FluxSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        detent = FluxSheetDetent.Full,
        title = title,
        header = { FluxSheetHeader(title = title, subtitle = hostName, onClose = onDismiss) },
    ) {
        ActivityBody()
    }
}

@Composable
private fun ColumnScope.ActivityBody() {
    val view = LocalDownloadsView.current
    val actions = LocalTaskActions.current
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val stats by rememberLiveStats()
    val wave = remember(view) { { view.wave.series } }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FluxText(stats.speed.value, style = t.display, color = if (stats.down > 0) c.accentHi else c.ink, maxLines = 1, softWrap = false)
        FluxText(stats.speed.unit, modifier = Modifier.padding(bottom = 10.dp), style = t.h2, color = c.inkMuted, maxLines = 1, softWrap = false)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FluxText("↑", style = t.h1, color = c.inkMuted, maxLines = 1)
        FluxText(stats.upSpeed.toString(), style = t.h1, color = c.inkMuted, maxLines = 1, softWrap = false)
    }

    Waveform(samples = wave, modifier = Modifier.padding(top = 12.dp).height(120.dp))
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(dashed = false, label = stringResource(R.string.mobileActivityDownload))
        LegendItem(dashed = true, label = stringResource(R.string.mobileActivityUpload))
    }

    GlassSection(modifier = Modifier.padding(top = 8.dp)) {
        custom {
            FluxStatRow(
                listOf(
                    StatItem(stats.active.toString(), stringResource(R.string.mobileInstrumentActive)),
                    StatItem(stats.pending.toString(), stringResource(R.string.mobileActivityQueued)),
                    StatItem(stats.retry.toString(), stringResource(R.string.mobileActivityRetry)),
                ),
            )
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FluxButton(
            text = stringResource(R.string.pauseAll),
            onClick = { actions.pauseAll() },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Secondary,
            icon = FluxIcons.Pause,
        )
        FluxButton(
            text = stringResource(R.string.resumeAll),
            onClick = { actions.resumeAll() },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Secondary,
            icon = FluxIcons.Play,
        )
    }

    GlassSection(modifier = Modifier.padding(top = 8.dp)) {
        row {
            FluxKeyValue(
                key = stringResource(R.string.mobileInstrumentFree),
                value = stats.diskFree?.let { Format.bytes(it).toString() } ?: "—",
                mono = true,
            )
        }
    }
}

/** 图例：实线（下行）/ 虚线（上行），与波形线型一致。 */
@Composable
private fun LegendItem(dashed: Boolean, label: String) {
    val c = FluxTheme.colors
    val color = if (dashed) c.inkMuted else c.accentHi
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 18.dp, height = 2.dp)
                .drawBehind {
                    val y = size.height / 2f
                    drawLine(
                        color,
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = if (dashed) 1.dp.toPx() else 1.6.dp.toPx(),
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())) else null,
                    )
                },
        )
        FluxText(label, style = FluxTheme.type.sm, color = c.inkMuted, maxLines = 1)
    }
}
