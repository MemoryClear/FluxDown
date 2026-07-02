import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shadcn_ui/shadcn_ui.dart';
import '../models/download_controller.dart';
import '../models/download_task.dart';
import '../models/settings_provider.dart';
import '../i18n/locale_provider.dart';
import '../theme/app_colors.dart';
import 'feedback_dialog.dart';

// 预设限速值（label 显示用，kbs 为 KB/s）
const _kPresets = [
  (label: '128 KB/s', kbs: 128),
  (label: '512 KB/s', kbs: 512),
  (label: '1 MB/s', kbs: 1024),
  (label: '2 MB/s', kbs: 2048),
  (label: '5 MB/s', kbs: 5120),
];

/// 将字节/秒格式化为可读速率字符串，整数不显示小数
String _formatSpeed(int bytes) {
  if (bytes >= 1024 * 1024) {
    final mb = bytes / (1024 * 1024);
    final rounded = mb.round();
    return rounded == mb ? '$rounded MB/s' : '${mb.toStringAsFixed(1)} MB/s';
  }
  return '${(bytes / 1024).round()} KB/s';
}

class StatusBar extends StatefulWidget {
  final DownloadController controller;
  final SettingsProvider settingsProvider;

  const StatusBar({
    super.key,
    required this.controller,
    required this.settingsProvider,
  });

  @override
  State<StatusBar> createState() => _StatusBarState();
}

class _StatusBarState extends State<StatusBar> {
  final _popoverController = ShadPopoverController();
  final _customController = TextEditingController();

  /// 上次已写入 settings 的字节数，用于防循环更新
  int _lastKnownBytes = -1;

  @override
  void initState() {
    super.initState();
    final bytes = widget.settingsProvider.speedLimitBytes;
    _lastKnownBytes = bytes;
    _customController.text = _kbsText(bytes);
    widget.settingsProvider.addListener(_onSettingsChanged);
    _popoverController.addListener(_onPopoverChanged);
  }

  @override
  void dispose() {
    _popoverController.removeListener(_onPopoverChanged);
    widget.settingsProvider.removeListener(_onSettingsChanged);
    _popoverController.dispose();
    _customController.dispose();
    super.dispose();
  }

  /// 将 bytes/s 转换为输入框文本（0 → 空字符串）
  String _kbsText(int bytes) {
    if (bytes <= 0) return '';
    return (bytes / 1024).round().toString();
  }

  /// 设置页（外部）修改限速时同步输入框
  void _onSettingsChanged() {
    final newBytes = widget.settingsProvider.speedLimitBytes;
    if (newBytes == _lastKnownBytes) return;
    _lastKnownBytes = newBytes;
    _customController.text = _kbsText(newBytes);
    if (mounted) setState(() {});
  }

  /// Popover 关闭时，若已开启限速，则将自定义输入框的当前值写入设置
  void _onPopoverChanged() {
    if (!_popoverController.isOpen) {
      _applyCustomInput();
    }
  }

  bool get _isLimited => widget.settingsProvider.speedLimitBytes > 0;

  /// 切换开关
  void _toggleLimit(bool on) {
    if (on) {
      final kbs = int.tryParse(_customController.text.trim()) ?? 0;
      final effectiveKbs = kbs > 0 ? kbs : 512;
      if (kbs <= 0) _customController.text = '512';
      final bytes = effectiveKbs * 1024;
      _lastKnownBytes = bytes;
      widget.settingsProvider.setSpeedLimitBytes(bytes);
    } else {
      _lastKnownBytes = 0;
      widget.settingsProvider.setSpeedLimitBytes(0);
    }
  }

  /// 点击预设：直接启用并应用该速率
  void _applyPreset(int kbs) {
    _customController.text = kbs.toString();
    final bytes = kbs * 1024;
    _lastKnownBytes = bytes;
    widget.settingsProvider.setSpeedLimitBytes(bytes);
  }

  /// 自定义输入框的值写入设置（仅限速已开启时有效）
  void _applyCustomInput() {
    if (!_isLimited) return;
    final kbs = int.tryParse(_customController.text.trim()) ?? 0;
    if (kbs > 0) {
      final bytes = kbs * 1024;
      if (bytes != _lastKnownBytes) {
        _lastKnownBytes = bytes;
        widget.settingsProvider.setSpeedLimitBytes(bytes);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    final s = LocaleScope.of(context);
    return ListenableBuilder(
      listenable: Listenable.merge([
        widget.controller,
        widget.settingsProvider,
      ]),
      builder: (context, _) {
        final dlSpeed = DownloadTask.formatBytes(
          widget.controller.totalDownloadSpeed,
        );
        final active = widget.controller.activeCount;
        final paused = widget.controller.pausedCount;
        final total = widget.controller.tasks.length;

        return Container(
          height: 28,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          decoration: BoxDecoration(
            color: c.surface1,
            border: Border(top: BorderSide(color: c.border, width: 1)),
          ),
          child: Row(
            children: [
              // 状态指示
              Row(
                children: [
                  Icon(
                    LucideIcons.circle,
                    size: 8,
                    color: active > 0 ? AppColors.green : c.textMuted,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    active > 0 ? s.statusDownloadingLabel : s.statusIdle,
                    style: TextStyle(fontSize: 10.5, color: c.textMuted),
                  ),
                ],
              ),
              const SizedBox(width: 20),
              // 实时下载速度
              Row(
                children: [
                  const Icon(
                    LucideIcons.arrowDown,
                    size: 10,
                    color: AppColors.green,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    '$dlSpeed/s',
                    style: TextStyle(
                      fontSize: 10.5,
                      color: c.textMuted,
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
              const SizedBox(width: 20),
              Text(
                s.statusSummary(active, paused, total),
                style: TextStyle(fontSize: 10.5, color: c.textMuted),
              ),
              const Spacer(),
              // 限速 Popover 触发器
              _SpeedLimitTrigger(
                popoverController: _popoverController,
                settingsProvider: widget.settingsProvider,
                customController: _customController,
                isLimited: _isLimited,
                limitBytes: widget.settingsProvider.speedLimitBytes,
                onToggle: _toggleLimit,
                onApplyPreset: _applyPreset,
                onApplyCustom: _applyCustomInput,
                s: s,
                c: c,
              ),
              const SizedBox(width: 12),
              Container(width: 1, height: 12, color: c.border),
              const SizedBox(width: 12),
              // 反馈按钮
              GestureDetector(
                onTap: () => showFeedbackDialog(context),
                child: MouseRegion(
                  cursor: SystemMouseCursors.click,
                  child: Row(
                    children: [
                      Icon(
                        LucideIcons.messageSquarePlus,
                        size: 11,
                        color: c.textMuted,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        s.feedback,
                        style: TextStyle(fontSize: 10.5, color: c.textMuted),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

// =============================================================================
// 触发器 Widget — 显示当前限速状态，点击展开/收起 Popover
// =============================================================================

class _SpeedLimitTrigger extends StatelessWidget {
  final ShadPopoverController popoverController;
  final SettingsProvider settingsProvider;
  final TextEditingController customController;
  final bool isLimited;
  final int limitBytes;
  final ValueChanged<bool> onToggle;
  final ValueChanged<int> onApplyPreset;
  final VoidCallback onApplyCustom;
  final S s;
  final AppColors c;

  const _SpeedLimitTrigger({
    required this.popoverController,
    required this.settingsProvider,
    required this.customController,
    required this.isLimited,
    required this.limitBytes,
    required this.onToggle,
    required this.onApplyPreset,
    required this.onApplyCustom,
    required this.s,
    required this.c,
  });

  @override
  Widget build(BuildContext context) {
    final triggerColor = isLimited ? c.accent : c.textMuted;
    final triggerText =
        isLimited ? _formatSpeed(limitBytes) : s.statusSpeedLimitOff;

    return ShadPopover(
      controller: popoverController,
      // 弹出在触发器上方，右对齐（状态栏位于屏幕底部）
      anchor: const ShadAnchorAuto(
        offset: Offset(0, -8),
        followerAnchor: Alignment.bottomRight,
        targetAnchor: Alignment.topRight,
      ),
      padding: EdgeInsets.zero,
      // 使用 ListenableBuilder 确保 Popover 内容在设置变更后自动刷新
      popover: (ctx) => ListenableBuilder(
        listenable: settingsProvider,
        builder: (ctx2, _) => _SpeedLimitPopoverContent(
          customController: customController,
          isLimited: settingsProvider.speedLimitBytes > 0,
          limitBytes: settingsProvider.speedLimitBytes,
          onToggle: onToggle,
          onApplyPreset: onApplyPreset,
          onApplyCustom: onApplyCustom,
          s: s,
          c: c,
        ),
      ),
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        child: GestureDetector(
          onTap: popoverController.toggle,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(LucideIcons.gauge, size: 11, color: triggerColor),
              const SizedBox(width: 4),
              Text(
                triggerText,
                style: TextStyle(
                  fontSize: 10.5,
                  color: triggerColor,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
              const SizedBox(width: 2),
              Icon(LucideIcons.chevronUp, size: 9, color: triggerColor),
            ],
          ),
        ),
      ),
    );
  }
}

// =============================================================================
// Popover 内容 — 开关 + 预设速率 + 自定义输入
// =============================================================================

class _SpeedLimitPopoverContent extends StatelessWidget {
  final TextEditingController customController;
  final bool isLimited;
  final int limitBytes;
  final ValueChanged<bool> onToggle;
  final ValueChanged<int> onApplyPreset;
  final VoidCallback onApplyCustom;
  final S s;
  final AppColors c;

  const _SpeedLimitPopoverContent({
    required this.customController,
    required this.isLimited,
    required this.limitBytes,
    required this.onToggle,
    required this.onApplyPreset,
    required this.onApplyCustom,
    required this.s,
    required this.c,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 220,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          // 标题行 + 开关
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 8, 10),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    s.speedLimitTitle,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: c.textPrimary,
                    ),
                  ),
                ),
                ShadSwitch(
                  value: isLimited,
                  onChanged: onToggle,
                  width: 34,
                  height: 18,
                  margin: 2,
                ),
              ],
            ),
          ),
          // 预设速率 chips
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
            child: Wrap(
              spacing: 5,
              runSpacing: 5,
              children: _kPresets.map((preset) {
                final isSelected = isLimited && limitBytes == preset.kbs * 1024;
                return MouseRegion(
                  cursor: SystemMouseCursors.click,
                  child: GestureDetector(
                    onTap: () => onApplyPreset(preset.kbs),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 120),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: isSelected ? c.accent : c.surface2,
                        borderRadius: BorderRadius.circular(4),
                        border: Border.all(
                          color: isSelected ? c.accent : c.border,
                          width: 0.5,
                        ),
                      ),
                      child: Text(
                        preset.label,
                        style: TextStyle(
                          fontSize: 11,
                          color: isSelected
                              ? const Color(0xFFFFFFFF)
                              : c.textSecondary,
                          fontFeatures: const [FontFeature.tabularFigures()],
                        ),
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          // 分割线
          Divider(color: c.border, height: 1),
          // 自定义输入
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  s.speedLimitCustom,
                  style: TextStyle(fontSize: 11, color: c.textMuted),
                ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    Expanded(
                      child: ShadInput(
                        controller: customController,
                        keyboardType: TextInputType.number,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                        ],
                        placeholder: Text(s.statusSpeedLimitHint),
                        onSubmitted: (_) => onApplyCustom(),
                      ),
                    ),
                    const SizedBox(width: 6),
                    Text(
                      s.statusSpeedLimitKbs,
                      style: TextStyle(fontSize: 12, color: c.textMuted),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
