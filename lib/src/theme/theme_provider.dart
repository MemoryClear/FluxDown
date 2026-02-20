import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../i18n/locale_provider.dart';
import 'app_theme.dart';

/// 支持的主题色方案（4 个预设 + 自定义）
enum AppColorScheme {
  blue(Color(0xFF3B82F6)),
  green(Color(0xFF22C55E)),
  violet(Color(0xFF8B5CF6)),
  rose(Color(0xFFF43F5E)),
  custom(Color(0xFF6366F1)); // 占位色，实际颜色由 ThemeProvider.customColor 决定

  final Color previewColor;
  const AppColorScheme(this.previewColor);
}

/// 国际化颜色名称
extension AppColorSchemeI18n on AppColorScheme {
  String get label {
    final s = currentS;
    return switch (this) {
      AppColorScheme.blue => s.colorBlue,
      AppColorScheme.green => s.colorGreen,
      AppColorScheme.violet => s.colorViolet,
      AppColorScheme.rose => s.colorRose,
      AppColorScheme.custom => s.colorCustom,
    };
  }
}

/// SharedPreferences 存储 key
const _kThemeMode = 'theme_mode';
const _kColorScheme = 'color_scheme';
const _kCustomColor = 'custom_color';

/// 全局主题模式 + 颜色方案管理（带 SharedPreferences 持久化）
class ThemeProvider extends ChangeNotifier {
  ThemeMode _themeMode = ThemeMode.system;
  AppColorScheme _colorScheme = AppColorScheme.blue;
  Color _customColor = const Color(0xFF6366F1); // 默认 indigo

  ThemeMode get themeMode => _themeMode;
  AppColorScheme get colorScheme => _colorScheme;
  Color get customColor => _customColor;

  /// 当前生效的预览色（预设返回枚举色，自定义返回用户选色）
  Color get activePreviewColor => _colorScheme == AppColorScheme.custom
      ? _customColor
      : _colorScheme.previewColor;

  /// 启动时调用，从 SharedPreferences 恢复上次保存的主题设置。
  /// 若无保存值则使用默认值（system + blue），不会 notifyListeners。
  Future<void> init() async {
    final prefs = await SharedPreferences.getInstance();

    final modeStr = prefs.getString(_kThemeMode);
    if (modeStr != null) {
      _themeMode = ThemeMode.values.firstWhere(
        (m) => m.name == modeStr,
        orElse: () => ThemeMode.system,
      );
    }

    final schemeStr = prefs.getString(_kColorScheme);
    if (schemeStr != null) {
      _colorScheme = AppColorScheme.values.firstWhere(
        (s) => s.name == schemeStr,
        orElse: () => AppColorScheme.blue,
      );
    }

    final customHex = prefs.getString(_kCustomColor);
    if (customHex != null) {
      final parsed = int.tryParse(customHex, radix: 16);
      if (parsed != null) {
        _customColor = Color(parsed);
      }
    }

    // 静默加载，不触发 rebuild（main.dart 会在 init 完成后才 runApp）
  }

  void setThemeMode(ThemeMode mode) {
    if (_themeMode == mode) return;
    _themeMode = mode;
    invalidateThemeCache();
    notifyListeners();
    _persist(_kThemeMode, mode.name);
  }

  void setColorScheme(AppColorScheme scheme) {
    if (_colorScheme == scheme) return;
    _colorScheme = scheme;
    invalidateThemeCache();
    notifyListeners();
    _persist(_kColorScheme, scheme.name);
  }

  /// 设置自定义颜色并自动切换到 custom 方案
  void setCustomColor(Color color) {
    _customColor = color;
    if (_colorScheme != AppColorScheme.custom) {
      _colorScheme = AppColorScheme.custom;
      _persist(_kColorScheme, AppColorScheme.custom.name);
    }
    invalidateThemeCache();
    notifyListeners();
    _persist(_kCustomColor, color.toARGB32().toRadixString(16).padLeft(8, '0'));
  }

  void toggleTheme(BuildContext context) {
    final brightness = MediaQuery.platformBrightnessOf(context);
    final isDark =
        _themeMode == ThemeMode.dark ||
        (_themeMode == ThemeMode.system && brightness == Brightness.dark);
    setThemeMode(isDark ? ThemeMode.light : ThemeMode.dark);
  }

  /// 获取当前实际是否为暗色模式
  bool isDark(BuildContext context) {
    if (_themeMode == ThemeMode.system) {
      return MediaQuery.platformBrightnessOf(context) == Brightness.dark;
    }
    return _themeMode == ThemeMode.dark;
  }

  /// 异步写入 SharedPreferences（fire-and-forget，不阻塞 UI）
  Future<void> _persist(String key, String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(key, value);
  }
}
