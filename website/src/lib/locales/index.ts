// 翻译源文件为 JSON（en.json 为源语言，社区经 Weblate 贡献其他语言）。
// 键集合以 en.json 为准；Messages 类型由 en.json 推导，保证键名类型安全。
import enJson from "./en.json";
import zhCNJson from "./zh-CN.json";

export type Messages = { [K in keyof typeof enJson]: string };

export const en: Messages = enJson;
export const zhCN: Messages = zhCNJson;
