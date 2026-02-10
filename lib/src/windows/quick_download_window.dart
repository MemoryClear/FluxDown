import 'dart:convert';

import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:shadcn_ui/shadcn_ui.dart';

import '../theme/app_colors.dart';
import '../theme/app_theme.dart';
import '../theme/theme_provider.dart';
import '../widgets/title_drag_area.dart';
import 'sub_window_utils.dart';

/// 独立快速下载确认窗口 — 浏览器扩展拦截下载时弹出
///
/// 作为独立子窗口运行，不影响主窗口。
/// 确认后通过 WindowController.invokeMethod 将数据回传主窗口。
class QuickDownloadWindow extends StatefulWidget {
  final WindowController windowController;
  final Map<String, dynamic> args;

  const QuickDownloadWindow({
    super.key,
    required this.windowController,
    required this.args,
  });

  @override
  State<QuickDownloadWindow> createState() => _QuickDownloadWindowState();
}

class _QuickDownloadWindowState extends State<QuickDownloadWindow> {
  final _saveDirController = TextEditingController();
  final _renameController = TextEditingController();
  String? selectedThreads;

  String get url => widget.args['url'] as String? ?? '';
  String get filename => widget.args['filename'] as String? ?? '';
  int get fileSize => widget.args['fileSize'] as int? ?? 0;
  String get mimeType => widget.args['mimeType'] as String? ?? '';
  String get cookies => widget.args['cookies'] as String? ?? '';
  String get defaultSaveDir => widget.args['defaultSaveDir'] as String? ?? '';
  String get mainWindowId => widget.args['mainWindowId'] as String? ?? '0';

  @override
  void initState() {
    super.initState();
    _saveDirController.text = defaultSaveDir;
    if (filename.isNotEmpty) {
      _renameController.text = filename;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => _initWindow());
  }

  void _initWindow() {
    SubWindowUtils.init();
    SubWindowUtils.removeCaption();
    SubWindowUtils.setSize(const Size(500, 400));
    SubWindowUtils.center();
    SubWindowUtils.setAlwaysOnTop(true);
    SubWindowUtils.setTitle('FluxDown - 新建下载');
    SubWindowUtils.show();
    SubWindowUtils.focus();
  }

  @override
  void dispose() {
    _saveDirController.dispose();
    _renameController.dispose();
    super.dispose();
  }

  Future<void> _pickSaveDir() async {
    final result = await FilePicker.platform.getDirectoryPath(
      dialogTitle: '选择保存目录',
      initialDirectory: _saveDirController.text.trim().isNotEmpty
          ? _saveDirController.text.trim()
          : null,
    );
    if (result != null) {
      _saveDirController.text = result;
    }
  }

  Future<void> _startDownload() async {
    final saveDir = _saveDirController.text.trim();
    if (saveDir.isEmpty) return;

    final rename = _renameController.text.trim();
    final segments = switch (selectedThreads) {
      '自动' => 0,
      '4' => 4,
      '8' => 8,
      '16' => 16,
      '32' => 32,
      '64' => 64,
      _ => 0,
    };

    try {
      final mainController = WindowController.fromWindowId(mainWindowId);
      await mainController.invokeMethod(
        'confirm_download',
        jsonEncode({
          'url': url,
          'saveDir': saveDir,
          'fileName': rename,
          'segments': segments,
          'cookies': cookies,
        }),
      );
    } catch (e) {
      debugPrint('[QuickDownloadWindow] invokeMethod error: $e');
    }

    SubWindowUtils.close();
  }

  void _cancel() {
    SubWindowUtils.close();
  }

  String _formatFileSize(int bytes) {
    if (bytes <= 0) return '未知大小';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    int unitIndex = 0;
    double size = bytes.toDouble();
    while (size >= 1024 && unitIndex < units.length - 1) {
      size /= 1024;
      unitIndex++;
    }
    return '${size.toStringAsFixed(unitIndex == 0 ? 0 : 1)} ${units[unitIndex]}';
  }

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);

    return Scaffold(
      backgroundColor: c.bg,
      body: Column(
        children: [
          // ===== 自定义标题栏 =====
          _TitleBar(c: c, onClose: _cancel),
          // ===== 内容区域 =====
          Expanded(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // 可滚动内容区
                  Expanded(
                    child: SingleChildScrollView(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const SizedBox(height: 16),
                          // 标题行 + 文件信息标签
                          Row(
                            children: [
                              Container(
                                width: 32,
                                height: 32,
                                decoration: BoxDecoration(
                                  color: c.accent.withValues(alpha: 0.1),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Icon(
                                  LucideIcons.download,
                                  size: 16,
                                  color: c.accent,
                                ),
                              ),
                              const SizedBox(width: 10),
                              Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '新建下载',
                                    style: TextStyle(
                                      fontSize: 15,
                                      fontWeight: FontWeight.w600,
                                      color: c.textPrimary,
                                    ),
                                  ),
                                  const SizedBox(height: 2),
                                  Row(
                                    children: [
                                      if (fileSize > 0)
                                        _InfoTag(
                                          text: _formatFileSize(fileSize),
                                          c: c,
                                        ),
                                      if (fileSize > 0 && mimeType.isNotEmpty)
                                        const SizedBox(width: 6),
                                      if (mimeType.isNotEmpty)
                                        _InfoTag(text: mimeType, c: c),
                                    ],
                                  ),
                                ],
                              ),
                            ],
                          ),

                          const SizedBox(height: 18),

                          // URL 显示
                          _SectionLabel(text: '下载链接', c: c),
                          const SizedBox(height: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 10,
                            ),
                            decoration: BoxDecoration(
                              color: c.surface2,
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(
                                color: c.border.withValues(alpha: 0.6),
                              ),
                            ),
                            child: SelectableText(
                              url,
                              style: TextStyle(
                                fontSize: 12,
                                color: c.textSecondary,
                                fontFamily: 'monospace',
                                height: 1.5,
                              ),
                              maxLines: 2,
                            ),
                          ),

                          const SizedBox(height: 16),

                          // 保存目录 + 线程数
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    _SectionLabel(text: '保存目录', c: c),
                                    const SizedBox(height: 6),
                                    GestureDetector(
                                      onTap: _pickSaveDir,
                                      child: AbsorbPointer(
                                        child: ShadInput(
                                          controller: _saveDirController,
                                          placeholder: const Text('选择保存目录'),
                                          readOnly: true,
                                          trailing: Padding(
                                            padding: const EdgeInsets.only(
                                              right: 4,
                                            ),
                                            child: Icon(
                                              LucideIcons.folderOpen,
                                              size: 14,
                                              color: c.textMuted,
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 12),
                              SizedBox(
                                width: 100,
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    _SectionLabel(text: '线程数', c: c),
                                    const SizedBox(height: 6),
                                    ShadSelect<String>(
                                      placeholder: const Text('自动'),
                                      options:
                                          ['自动', '4', '8', '16', '32', '64']
                                              .map(
                                                (e) => ShadOption(
                                                  value: e,
                                                  child: Text(e),
                                                ),
                                              )
                                              .toList(),
                                      selectedOptionBuilder: (context, value) =>
                                          Text(value),
                                      onChanged: (v) =>
                                          setState(() => selectedThreads = v),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),

                          const SizedBox(height: 16),

                          // 文件名
                          _SectionLabel(text: '文件名（留空自动识别）', c: c),
                          const SizedBox(height: 6),
                          ShadInput(
                            controller: _renameController,
                            placeholder: const Text('自动识别文件名'),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),

                  // 底部按钮（固定在底部）
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      ShadButton.outline(
                        onPressed: _cancel,
                        child: const Text('取消'),
                      ),
                      const SizedBox(width: 8),
                      ShadButton(
                        onPressed: _startDownload,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              LucideIcons.download,
                              size: 14,
                              color: Colors.white,
                            ),
                            const SizedBox(width: 6),
                            const Text(
                              '开始下载',
                              style: TextStyle(color: Colors.white),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 信息标签（文件大小 / MIME 类型）
class _InfoTag extends StatelessWidget {
  final String text;
  final AppColors c;

  const _InfoTag({required this.text, required this.c});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: c.surface2,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(text, style: TextStyle(fontSize: 10, color: c.textMuted)),
    );
  }
}

/// 表单分区标签
class _SectionLabel extends StatelessWidget {
  final String text;
  final AppColors c;

  const _SectionLabel({required this.text, required this.c});

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: TextStyle(
        fontSize: 11.5,
        fontWeight: FontWeight.w500,
        color: c.textSecondary,
      ),
    );
  }
}

/// 子窗口自定义标题栏 — 拖拽移动 + 关闭按钮，精致设计
class _TitleBar extends StatefulWidget {
  final AppColors c;
  final VoidCallback onClose;

  const _TitleBar({required this.c, required this.onClose});

  @override
  State<_TitleBar> createState() => _TitleBarState();
}

class _TitleBarState extends State<_TitleBar> {
  bool _isCloseHovered = false;

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    return TitleDragArea(
      child: Container(
        height: 36,
        padding: const EdgeInsets.only(left: 14),
        decoration: BoxDecoration(
          color: c.surface1,
          border: Border(bottom: BorderSide(color: c.border, width: 1)),
        ),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: Image.asset(
                'assets/logo/fluxdown_logo.png',
                width: 16,
                height: 16,
                filterQuality: FilterQuality.medium,
                errorBuilder: (_, __, _) =>
                    Icon(LucideIcons.download, size: 14, color: c.accent),
              ),
            ),
            const SizedBox(width: 8),
            Text(
              'FluxDown',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: c.textPrimary,
                letterSpacing: 0.2,
              ),
            ),
            const Spacer(),
            // 关闭按钮
            MouseRegion(
              onEnter: (_) => setState(() => _isCloseHovered = true),
              onExit: (_) => setState(() => _isCloseHovered = false),
              child: GestureDetector(
                onTap: widget.onClose,
                child: Container(
                  width: 42,
                  height: 36,
                  color: _isCloseHovered
                      ? AppColors.red.withValues(alpha: 0.9)
                      : Colors.transparent,
                  child: Icon(
                    LucideIcons.x,
                    size: 14,
                    color: _isCloseHovered ? Colors.white : c.textMuted,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 子窗口入口 App — 包装 shadcn_ui 主题
class QuickDownloadApp extends StatelessWidget {
  final WindowController windowController;
  final Map<String, dynamic> args;

  const QuickDownloadApp({
    super.key,
    required this.windowController,
    required this.args,
  });

  @override
  Widget build(BuildContext context) {
    final schemeName = args['colorScheme'] as String? ?? 'blue';
    final isDark = args['isDark'] as bool? ?? true;

    final scheme = AppColorScheme.values.firstWhere(
      (s) => s.name == schemeName,
      orElse: () => AppColorScheme.blue,
    );

    return ShadApp(
      debugShowCheckedModeBanner: false,
      themeMode: isDark ? ThemeMode.dark : ThemeMode.light,
      theme: buildLightTheme(scheme),
      darkTheme: buildDarkTheme(scheme),
      home: QuickDownloadWindow(windowController: windowController, args: args),
    );
  }
}
