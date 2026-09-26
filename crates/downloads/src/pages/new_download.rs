//! 「新建下载」表单：字段、顺序与提交语义与
//! `lib/src/widgets/new_download_dialog.dart` 逐条对齐；纯规则见
//! [`crate::model::new_download`]。

use std::{collections::HashMap, path::PathBuf, rc::Rc};

use crate::{
    model::new_download::{
        DEFAULT_HASH_ALGORITHM, DraftOptions, HASH_ALGORITHMS, MAX_THREADS, ProxyChoice,
        THREAD_PRESETS, ThreadChoice, UA_PRESET_CUSTOM, UA_PRESET_DEFAULT, UrlEntry,
        build_requests, checksum_spec, custom_segments, detect_ua_preset, merge_imported,
        parse_entries, ua_preset_keys, ua_preset_value,
    },
    strings::NewDownloadStrings,
};
use fluxdown_protocol::CreateTaskRequest;
use fluxdown_ui_components::{
    ControlExt as _, FluxIcon, IconControlExt as _, field_error, field_hint, field_label, form,
    form_field, form_gap, form_row, input_with_action, option_group, option_row,
};
use fluxdown_ui_i18n::Translator;
use fluxdown_ui_theme::{CONTROL_HEIGHT, active_theme};
use gpui::{
    Anchor, AnyElement, App, AppContext as _, ClickEvent, Context, Div, Entity, FontWeight,
    InteractiveElement as _, IntoElement, ParentElement, Pixels, Render, SharedString,
    StatefulInteractiveElement as _, Styled, Window, div, prelude::FluentBuilder as _, px,
};
use gpui_component::{
    Disableable as _, Icon, Sizable as _, Size, WindowExt as _,
    button::{Button, ButtonVariants as _, DropdownButton},
    h_flex,
    input::{Input, InputEvent, InputState, Textarea, TextareaState},
    menu::{DropdownMenu as _, PopupMenu, PopupMenuItem},
    notification::Notification,
    scroll::ScrollableElement as _,
    switch::Switch,
    v_flex,
};

/// 「自定义线程数」数字输入宽度。
const CUSTOM_THREADS_WIDTH: Pixels = px(96.);
/// 预设下拉（UA、校验算法）定宽，右侧输入框吃满剩余宽度。
const PRESET_DROPDOWN_WIDTH: Pixels = px(140.);
/// 请求头名称列宽。
const HEADER_NAME_WIDTH: Pixels = px(168.);

/// 队列下拉候选（显示名由表单按内置队列本地化）。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NewDownloadQueue {
    pub id: String,
    pub name: String,
}

/// 表单打开时的环境快照：默认值与下拉候选，由宿主从 daemon 快照 / 偏好投影。
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct NewDownloadContext {
    /// 保存目录初值（沿用上次 / 全局默认）。
    pub save_dir: String,
    /// 「开始下载」默认落入的队列。
    pub queue_id: String,
    /// 线程数初值；0 = 自动。
    pub segments: i32,
    /// 队列清单（快照顺序）。
    pub queues: Vec<NewDownloadQueue>,
    /// 全局手动代理 URL；空 = 未配置（对应下拉项置灰）。
    pub manual_proxy_url: String,
    /// 预填链接（拖放 / 捕获「更多选项」）。
    pub initial_urls: Vec<String>,
    /// 预填文件名（仅单链接时有意义）。
    pub initial_file_name: String,
}

/// 表单确认后的提交内容。
#[derive(Clone, Debug)]
pub enum NewDownloadSubmission {
    /// 每条链接一个请求，共享表单选项；宿主逐条调用 `daemon.task.create`。
    Tasks(Vec<CreateTaskRequest>),
    /// 本机 `.torrent` 文件，交给 agent 读取上传。
    TorrentFiles(Vec<PathBuf>),
}

/// 新建下载对话框的提交回调。
pub type NewDownloadSubmit = Rc<dyn Fn(NewDownloadSubmission, &mut Window, &mut App)>;

struct HeaderRow {
    id: usize,
    key: Entity<InputState>,
    value: Entity<InputState>,
}

/// 独立窗口承载的「新建下载」表单。
///
/// 提交或取消都会关闭自身所在窗口；任务创建失败的提示由
/// [`super::downloads::DownloadView`] 展示。
pub struct NewDownloadView {
    strings: NewDownloadStrings,
    context: NewDownloadContext,
    on_submit: NewDownloadSubmit,
    urls: Entity<TextareaState>,
    entries: Vec<UrlEntry>,
    save_dir: Entity<InputState>,
    threads: ThreadChoice,
    custom_threads: Entity<InputState>,
    rename: Entity<InputState>,
    advanced_open: bool,
    http_user: Entity<InputState>,
    http_password: Entity<InputState>,
    save_site_auth: bool,
    proxy_choice: ProxyChoice,
    custom_proxy: Entity<InputState>,
    ignore_tls_errors: bool,
    ua_preset: &'static str,
    user_agent: Entity<InputState>,
    cookie: Entity<TextareaState>,
    hash_algorithm: &'static str,
    checksum: Entity<InputState>,
    headers: Vec<HeaderRow>,
    header_seq: usize,
    picking: bool,
}

impl NewDownloadView {
    /// 创建表单并聚焦链接输入框。
    pub fn new(
        translator: Entity<Translator>,
        context: NewDownloadContext,
        on_submit: NewDownloadSubmit,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let strings = NewDownloadStrings::from_translator(translator.read(cx));
        let urls = cx.new(|cx| {
            TextareaState::new(window, cx)
                .default_value(context.initial_urls.join("\n"))
                .placeholder(strings.url_placeholder.clone())
        });
        let save_dir = cx.new(|cx| {
            InputState::new(window, cx)
                .default_value(context.save_dir.clone())
                .placeholder(strings.save_dir_placeholder.clone())
        });
        let custom_threads = cx.new(|cx| {
            InputState::new(window, cx)
                .default_value(if context.segments > 0 {
                    context.segments.to_string()
                } else {
                    String::new()
                })
                .placeholder(strings.threads_custom_hint.clone())
                .validate(|text, _| text.chars().all(|c| c.is_ascii_digit()))
        });
        let http_password = cx.new(|cx| {
            InputState::new(window, cx)
                .masked(true)
                .placeholder(strings.http_auth_password.clone())
        });
        let cookie = cx.new(|cx| {
            TextareaState::new(window, cx).placeholder(strings.cookie_placeholder.clone())
        });
        let rename = cx.new(|cx| {
            InputState::new(window, cx)
                .default_value(context.initial_file_name.clone())
                .placeholder(strings.rename_placeholder.clone())
        });
        urls.update(cx, |input, cx| input.focus(window, cx));

        let mut this = Self {
            threads: ThreadChoice::from_segments(context.segments),
            rename,
            http_user: Self::input(strings.http_auth_user.clone(), window, cx),
            custom_proxy: Self::input(strings.proxy_placeholder.clone(), window, cx),
            user_agent: Self::input(strings.user_agent_desc.clone(), window, cx),
            checksum: Self::input(strings.checksum_placeholder.clone(), window, cx),
            strings,
            context,
            on_submit,
            urls,
            entries: Vec::new(),
            save_dir,
            custom_threads,
            advanced_open: false,
            http_password,
            save_site_auth: false,
            proxy_choice: ProxyChoice::FollowGlobal,
            ignore_tls_errors: false,
            ua_preset: UA_PRESET_DEFAULT,
            cookie,
            hash_algorithm: DEFAULT_HASH_ALGORITHM,
            headers: Vec::new(),
            header_seq: 0,
            picking: false,
        };
        this.refresh_entries(cx);
        this.subscribe_inputs(&translator, window, cx);
        this
    }

    fn input(
        placeholder: SharedString,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Entity<InputState> {
        cx.new(|cx| InputState::new(window, cx).placeholder(placeholder))
    }

    /// 文案随语言切换；链接文本每次变化重新解析（驱动计数 / 批量态 / 按钮
    /// 可用性），⌘/Ctrl+Enter 提交；单行输入框 Enter 提交；UA 手动编辑时反推预设。
    fn subscribe_inputs(
        &self,
        translator: &Entity<Translator>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        cx.observe(translator, |this, translator, cx| {
            this.strings = NewDownloadStrings::from_translator(translator.read(cx));
            cx.notify();
        })
        .detach();
        cx.subscribe_in(
            &self.urls,
            window,
            |this, _, event: &InputEvent, window, cx| match event {
                InputEvent::Change => this.refresh_entries(cx),
                InputEvent::PressEnter {
                    secondary: true, ..
                } => this.submit(false, None, window, cx),
                _ => {}
            },
        )
        .detach();
        for input in [&self.save_dir, &self.custom_threads, &self.rename] {
            cx.subscribe_in(
                input,
                window,
                |this, _, event: &InputEvent, window, cx| match event {
                    InputEvent::Change => cx.notify(),
                    InputEvent::PressEnter { shift: false, .. } => {
                        this.submit(false, None, window, cx);
                    }
                    _ => {}
                },
            )
            .detach();
        }
        cx.subscribe(&self.user_agent, |this, input, event: &InputEvent, cx| {
            if let InputEvent::Change = event {
                let detected = detect_ua_preset(&input.read(cx).value());
                if detected != this.ua_preset {
                    this.ua_preset = detected;
                    cx.notify();
                }
            }
        })
        .detach();
    }

    fn refresh_entries(&mut self, cx: &mut Context<Self>) {
        self.entries = parse_entries(&self.urls.read(cx).value(), false);
        cx.notify();
    }

    fn is_batch(&self) -> bool {
        self.entries.len() > 1
    }

    fn all_magnet(&self) -> bool {
        !self.entries.is_empty()
            && self.entries.iter().all(|entry| {
                entry
                    .url
                    .get(..7)
                    .is_some_and(|s| s.eq_ignore_ascii_case("magnet:"))
            })
    }

    fn can_submit(&self, cx: &App) -> bool {
        !self.picking
            && !self.entries.is_empty()
            && !self.save_dir.read(cx).value().trim().is_empty()
    }

    fn segments(&self, cx: &App) -> i32 {
        match self.threads {
            ThreadChoice::Auto => 0,
            ThreadChoice::Preset(value) => value.min(MAX_THREADS),
            ThreadChoice::Custom => custom_segments(&self.custom_threads.read(cx).value()),
        }
    }

    fn header_map(&self, cx: &App) -> HashMap<String, String> {
        let mut map = HashMap::new();
        for row in &self.headers {
            let key = row.key.read(cx).value().trim().to_owned();
            if key.is_empty() {
                continue;
            }
            map.insert(key, row.value.read(cx).value().trim().to_owned());
        }
        map
    }

    /// 草稿 → 请求（与 Dart `_startDownloadInner` 同序同规则）。
    fn draft_options(&self, later: bool, queue_override: Option<String>, cx: &App) -> DraftOptions {
        let queue_id = queue_override.unwrap_or_else(|| {
            if later {
                fluxdown_protocol::LATER_QUEUE_ID.to_owned()
            } else {
                self.context.queue_id.clone()
            }
        });
        DraftOptions {
            save_dir: self.save_dir.read(cx).value().trim().to_owned(),
            segments: self.segments(cx),
            cookies: self.cookie.read(cx).value().trim().to_owned(),
            proxy_url: self.proxy_choice.wire(
                &self.context.manual_proxy_url,
                &self.custom_proxy.read(cx).value(),
            ),
            user_agent: self.user_agent.read(cx).value().trim().to_owned(),
            queue_id,
            checksum: checksum_spec(self.hash_algorithm, &self.checksum.read(cx).value()),
            ignore_tls_errors: self.ignore_tls_errors,
            headers: self.header_map(cx),
            start_paused: later,
            rename: self.rename.read(cx).value().trim().to_owned(),
            http_user: self.http_user.read(cx).value().trim().to_owned(),
            http_password: self.http_password.read(cx).value().to_string(),
            save_site_auth: self.save_site_auth,
        }
    }

    fn submit(
        &mut self,
        later: bool,
        queue_override: Option<String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.can_submit(cx) {
            return;
        }
        let options = self.draft_options(later, queue_override, cx);
        let requests = build_requests(&self.entries, &options);
        (self.on_submit)(NewDownloadSubmission::Tasks(requests), window, cx);
        window.remove_window();
    }

    fn pick_torrent_files(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.picking {
            return;
        }
        self.picking = true;
        cx.notify();
        let receiver = cx.prompt_for_paths(gpui::PathPromptOptions {
            files: true,
            directories: false,
            multiple: true,
            prompt: Some(self.strings.select_torrent.clone()),
        });
        cx.spawn_in(window, async move |this, cx| {
            let paths = match receiver.await {
                Ok(Ok(Some(paths))) => paths
                    .into_iter()
                    .filter(|path| has_extension(path, &["torrent"]))
                    .collect::<Vec<_>>(),
                _ => Vec::new(),
            };
            let _ = this.update_in(cx, |this, window, cx| {
                this.picking = false;
                cx.notify();
                if paths.is_empty() {
                    return;
                }
                (this.on_submit)(NewDownloadSubmission::TorrentFiles(paths), window, cx);
                window.remove_window();
            });
        })
        .detach();
    }

    fn import_txt_files(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.picking {
            return;
        }
        self.picking = true;
        cx.notify();
        let receiver = cx.prompt_for_paths(gpui::PathPromptOptions {
            files: true,
            directories: false,
            multiple: true,
            prompt: Some(self.strings.import_txt.clone()),
        });
        cx.spawn_in(window, async move |this, cx| {
            let paths = match receiver.await {
                Ok(Ok(Some(paths))) => paths
                    .into_iter()
                    .filter(|path| has_extension(path, &["txt", "text"]))
                    .collect::<Vec<_>>(),
                _ => Vec::new(),
            };
            let cancelled = paths.is_empty();
            // 读文件在后台线程：单文件读取失败跳过，继续处理其他文件。
            let imported = cx
                .background_executor()
                .spawn(async move {
                    paths
                        .iter()
                        .filter_map(|path| std::fs::read_to_string(path).ok())
                        .flat_map(|content| parse_entries(&content, true))
                        .collect::<Vec<_>>()
                })
                .await;
            let _ = this.update_in(cx, |this, window, cx| {
                this.picking = false;
                cx.notify();
                if cancelled {
                    return;
                }
                if imported.is_empty() {
                    window.push_notification(
                        Notification::warning(this.strings.import_txt_none.clone()),
                        cx,
                    );
                    return;
                }
                let count = imported.len();
                let merged = merge_imported(&this.urls.read(cx).value(), imported);
                this.urls
                    .update(cx, |input, cx| input.set_value(merged, window, cx));
                this.refresh_entries(cx);
                window.push_notification(
                    Notification::success(this.strings.format_import_found(count)),
                    cx,
                );
            });
        })
        .detach();
    }

    fn pick_save_dir(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.picking {
            return;
        }
        self.picking = true;
        cx.notify();
        let receiver = cx.prompt_for_paths(gpui::PathPromptOptions {
            files: false,
            directories: true,
            multiple: false,
            prompt: Some(self.strings.save_dir_placeholder.clone()),
        });
        cx.spawn_in(window, async move |this, cx| {
            let picked = match receiver.await {
                Ok(Ok(Some(paths))) => paths.first().map(|path| path.display().to_string()),
                _ => None,
            };
            let _ = this.update_in(cx, |this, window, cx| {
                this.picking = false;
                if let Some(path) = picked {
                    this.save_dir
                        .update(cx, |input, cx| input.set_value(path, window, cx));
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn add_header_row(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.header_seq += 1;
        let key_hint = self.strings.header_name.clone();
        let value_hint = self.strings.header_value.clone();
        let key = cx.new(|cx| InputState::new(window, cx).placeholder(key_hint));
        let value = cx.new(|cx| InputState::new(window, cx).placeholder(value_hint));
        self.headers.push(HeaderRow {
            id: self.header_seq,
            key,
            value,
        });
        cx.notify();
    }

    fn set_threads(&mut self, choice: ThreadChoice, window: &mut Window, cx: &mut Context<Self>) {
        if choice == ThreadChoice::Custom && self.threads != ThreadChoice::Custom {
            // 进入自定义：若当前是数字预设则预填，便于快速编辑。
            let prefill = match self.threads {
                ThreadChoice::Preset(value) => value.to_string(),
                _ => String::new(),
            };
            self.custom_threads.update(cx, |input, cx| {
                input.set_value(prefill, window, cx);
                input.focus(window, cx);
            });
        }
        self.threads = choice;
        cx.notify();
    }

    fn set_ua_preset(&mut self, key: &'static str, window: &mut Window, cx: &mut Context<Self>) {
        self.ua_preset = key;
        if key != UA_PRESET_CUSTOM {
            let value = ua_preset_value(key);
            self.user_agent
                .update(cx, |input, cx| input.set_value(value, window, cx));
        }
        cx.notify();
    }

    fn queue_label(&self, queue_id: &str) -> SharedString {
        self.context
            .queues
            .iter()
            .find(|queue| queue.id == queue_id)
            .map_or_else(
                || self.strings.queue_name(queue_id, queue_id),
                |queue| self.strings.queue_name(&queue.id, &queue.name),
            )
    }

    fn proxy_label(&self, choice: ProxyChoice) -> SharedString {
        match choice {
            ProxyChoice::FollowGlobal => self.strings.proxy_follow.clone(),
            ProxyChoice::Direct => self.strings.proxy_direct.clone(),
            ProxyChoice::System => self.strings.proxy_system.clone(),
            ProxyChoice::GlobalManual => self.strings.proxy_global_manual.clone(),
            ProxyChoice::Custom => self.strings.proxy_custom.clone(),
        }
    }

    fn ua_label(&self, key: &str) -> SharedString {
        match key {
            "chrome" => self.strings.ua_chrome.clone(),
            "firefox" => self.strings.ua_firefox.clone(),
            "edge" => self.strings.ua_edge.clone(),
            "safari" => self.strings.ua_safari.clone(),
            UA_PRESET_CUSTOM => self.strings.ua_custom.clone(),
            _ => self.strings.ua_inherit.clone(),
        }
    }

    fn threads_label(&self, choice: ThreadChoice) -> SharedString {
        match choice {
            ThreadChoice::Auto => self.strings.threads_auto.clone(),
            ThreadChoice::Preset(value) => SharedString::from(value.to_string()),
            ThreadChoice::Custom => self.strings.threads_custom.clone(),
        }
    }

    /// 单选下拉：当前项打勾，禁用项置灰；选择后经弱引用回写表单。
    /// `width = None` 时铺满所在字段宽度（与输入框同高同宽）。
    fn dropdown<T: Clone + PartialEq + 'static>(
        &self,
        id: &'static str,
        width: Option<Pixels>,
        current: T,
        options: Vec<(T, SharedString, bool)>,
        on_pick: impl Fn(&mut Self, T, &mut Window, &mut Context<Self>) + 'static,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let label = options
            .iter()
            .find(|(value, _, _)| *value == current)
            .map(|(_, label, _)| label.clone())
            .unwrap_or_default();
        let this = cx.weak_entity();
        let on_pick = Rc::new(on_pick);
        Button::new(id)
            .outline()
            .control(cx)
            .label(label)
            .dropdown_caret(true)
            .map(|button| match width {
                Some(width) => button.flex_none().w(width),
                None => button.w_full(),
            })
            .dropdown_menu_with_anchor(Anchor::TopLeft, move |menu, _, _| {
                options.iter().fold(menu, |menu, (value, label, disabled)| {
                    let checked = *value == current;
                    let this = this.clone();
                    let value = value.clone();
                    let on_pick = on_pick.clone();
                    menu.item(
                        PopupMenuItem::new(label.clone())
                            .checked(checked)
                            .disabled(*disabled)
                            .on_click(move |_, window, cx| {
                                let value = value.clone();
                                let _ = this.update(cx, |this, cx| {
                                    on_pick(this, value, window, cx);
                                });
                            }),
                    )
                })
            })
            .into_any_element()
    }

    /// 队列选择菜单：选中即以该队列提交（`later` 决定是否暂停）。
    fn queue_menu(
        &self,
        later: bool,
        cx: &mut Context<Self>,
    ) -> impl Fn(PopupMenu, &mut Window, &mut Context<PopupMenu>) -> PopupMenu + 'static {
        let this = cx.weak_entity();
        let queues = self
            .context
            .queues
            .iter()
            .map(|queue| {
                (
                    queue.id.clone(),
                    self.strings.queue_name(&queue.id, &queue.name),
                )
            })
            .collect::<Vec<_>>();
        move |menu, _, _| {
            queues.iter().fold(menu, |menu, (queue_id, label)| {
                let this = this.clone();
                let queue_id = queue_id.clone();
                menu.item(
                    PopupMenuItem::new(label.clone()).on_click(move |_, window, cx| {
                        let queue_id = queue_id.clone();
                        let _ = this.update(cx, |this, cx| {
                            this.submit(later, Some(queue_id), window, cx);
                        });
                    }),
                )
            })
        }
    }

    /// 多行输入（链接 / Cookie）：13px 正文、宽松内边距，与单行输入框同一视觉体系。
    fn textarea(state: &Entity<TextareaState>, height: Pixels, cx: &App) -> Textarea {
        let tokens = active_theme(cx).tokens();
        Textarea::new(state)
            .h(height)
            .w_full()
            .px(tokens.spacing.md)
            .py(tokens.spacing.sm)
            .text_size(tokens.typography.sm.size)
            .line_height(tokens.typography.sm.line_height)
    }

    /// 开关行（放进 `option_group`）。
    fn toggle(
        &self,
        id: &'static str,
        title: SharedString,
        description: Option<SharedString>,
        checked: bool,
        on_change: fn(&mut Self, bool),
        cx: &mut Context<Self>,
    ) -> AnyElement {
        option_row(
            title,
            description,
            Switch::new(id).checked(checked).on_click(cx.listener(
                move |this, checked: &bool, _, cx| {
                    on_change(this, *checked);
                    cx.notify();
                },
            )),
            cx,
        )
        .into_any_element()
    }

    /// 窗口标题栏已显示「新建下载」，这里只保留一行说明，避免标题重复。
    fn render_header(&self, cx: &App) -> Div {
        let tokens = active_theme(cx).tokens();
        div()
            .flex_none()
            .px(tokens.spacing.lg)
            .pt(tokens.spacing.md)
            .pb(tokens.spacing.sm)
            .child(field_hint(self.strings.subtitle.clone(), cx))
    }

    /// 链接：标签行右侧显示已识别条数；多行输入下方是种子 / 文本导入入口。
    fn render_urls(&self, cx: &mut Context<Self>) -> Div {
        let spacing = active_theme(cx).tokens().spacing;
        let count = self.entries.len();
        let has_text = !self.urls.read(cx).value().trim().is_empty();
        v_flex()
            .w_full()
            .gap(spacing.xs + spacing.xxs)
            .child(
                h_flex()
                    .justify_between()
                    .items_center()
                    .child(field_label(self.strings.url_label.clone(), cx))
                    .when(count > 0, |this| {
                        this.child(field_hint(self.strings.format_url_count(count), cx))
                    }),
            )
            .child(Self::textarea(&self.urls, px(128.), cx))
            .when(has_text && count == 0, |this| {
                this.child(field_error(self.strings.no_valid_url.clone(), cx))
            })
            .child(
                h_flex()
                    .gap(spacing.sm)
                    .child(
                        Button::new("new-download-open-torrent")
                            .ghost()
                            .icon(FluxIcon::FolderOpen)
                            .label(self.strings.open_torrent.clone())
                            .control(cx)
                            .disabled(self.picking)
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                this.pick_torrent_files(window, cx);
                            })),
                    )
                    .child(
                        Button::new("new-download-import-txt")
                            .ghost()
                            .icon(FluxIcon::FileText)
                            .label(self.strings.import_txt.clone())
                            .control(cx)
                            .disabled(self.picking)
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                this.import_txt_files(window, cx);
                            })),
                    ),
            )
    }

    fn render_save_dir(&self, cx: &mut Context<Self>) -> Div {
        form_field(
            self.strings.save_dir.clone(),
            input_with_action(
                Input::new(&self.save_dir).control(cx).w_full(),
                Button::new("new-download-browse")
                    .outline()
                    .icon(FluxIcon::FolderOpen)
                    .label(self.strings.browse.clone())
                    .control(cx)
                    .disabled(self.picking)
                    .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                        this.pick_save_dir(window, cx);
                    })),
                cx,
            ),
            None,
            cx,
        )
    }

    /// 线程数：下拉铺满字段；选「自定义」时右侧追加数字输入。
    fn render_threads(&self, cx: &mut Context<Self>) -> Div {
        let spacing = active_theme(cx).tokens().spacing;
        let mut options = vec![(ThreadChoice::Auto, self.strings.threads_auto.clone(), false)];
        options.extend(THREAD_PRESETS.iter().map(|value| {
            (
                ThreadChoice::Preset(*value),
                self.threads_label(ThreadChoice::Preset(*value)),
                false,
            )
        }));
        options.push((
            ThreadChoice::Custom,
            self.strings.threads_custom.clone(),
            false,
        ));
        let dropdown = self.dropdown(
            "new-download-threads",
            None,
            self.threads,
            options,
            |this, choice, window, cx| this.set_threads(choice, window, cx),
            cx,
        );
        let control = h_flex()
            .w_full()
            .gap(spacing.sm)
            .child(div().flex_1().min_w_0().child(dropdown))
            .when(self.threads == ThreadChoice::Custom, |this| {
                this.child(
                    Input::new(&self.custom_threads)
                        .control(cx)
                        .flex_none()
                        .w(CUSTOM_THREADS_WIDTH),
                )
            });
        form_field(self.strings.threads.clone(), control, None, cx)
    }

    /// 文件名 | 线程数并排；批量隐藏文件名，全磁力隐藏线程数。
    fn render_name_threads(&self, cx: &mut Context<Self>) -> Option<Div> {
        let mut fields = Vec::with_capacity(2);
        if !self.is_batch() {
            fields.push(
                form_field(
                    self.strings.rename.clone(),
                    Input::new(&self.rename).control(cx).w_full(),
                    None,
                    cx,
                )
                .into_any_element(),
            );
        }
        if !self.all_magnet() {
            fields.push(self.render_threads(cx).into_any_element());
        }
        (!fields.is_empty()).then(|| form_row(fields, cx))
    }

    fn render_http_auth(&self, cx: &mut Context<Self>) -> Div {
        form_field(
            self.strings.http_auth.clone(),
            form_row(
                [
                    Input::new(&self.http_user)
                        .control(cx)
                        .w_full()
                        .into_any_element(),
                    Input::new(&self.http_password)
                        .control(cx)
                        .mask_toggle()
                        .w_full()
                        .into_any_element(),
                ],
                cx,
            ),
            Some(self.strings.http_auth_desc.clone()),
            cx,
        )
    }

    fn render_proxy(&self, cx: &mut Context<Self>) -> Div {
        let spacing = active_theme(cx).tokens().spacing;
        let manual = self.context.manual_proxy_url.clone();
        let options = ProxyChoice::ALL
            .into_iter()
            .map(|choice| {
                let (label, disabled) = match choice {
                    ProxyChoice::GlobalManual if manual.is_empty() => (
                        SharedString::from(format!(
                            "{}（{}）",
                            self.proxy_label(choice),
                            self.strings.proxy_not_configured
                        )),
                        true,
                    ),
                    ProxyChoice::GlobalManual => (
                        SharedString::from(format!("{} · {manual}", self.proxy_label(choice))),
                        false,
                    ),
                    _ => (self.proxy_label(choice), false),
                };
                (choice, label, disabled)
            })
            .collect();
        let dropdown = self.dropdown(
            "new-download-proxy",
            None,
            self.proxy_choice,
            options,
            |this, choice, _, cx| {
                this.proxy_choice = choice;
                cx.notify();
            },
            cx,
        );
        let control = v_flex()
            .w_full()
            .gap(spacing.sm)
            .child(dropdown)
            .when(self.proxy_choice == ProxyChoice::Custom, |this| {
                this.child(Input::new(&self.custom_proxy).control(cx).w_full())
            });
        form_field(
            self.strings.proxy.clone(),
            control,
            Some(self.strings.proxy_desc.clone()),
            cx,
        )
    }

    /// 预设下拉 + 同行输入框（UA、校验和）：下拉定宽，输入框吃满剩余宽度。
    fn preset_with_input(preset: AnyElement, input: &Entity<InputState>, cx: &App) -> Div {
        let spacing = active_theme(cx).tokens().spacing;
        h_flex().w_full().gap(spacing.sm).child(preset).child(
            div()
                .flex_1()
                .min_w_0()
                .child(Input::new(input).control(cx).w_full()),
        )
    }

    fn render_user_agent(&self, cx: &mut Context<Self>) -> Div {
        let options = ua_preset_keys()
            .map(|key| (key, self.ua_label(key), false))
            .collect();
        let preset = self.dropdown(
            "new-download-ua-preset",
            Some(PRESET_DROPDOWN_WIDTH),
            self.ua_preset,
            options,
            |this, key, window, cx| this.set_ua_preset(key, window, cx),
            cx,
        );
        form_field(
            self.strings.user_agent.clone(),
            Self::preset_with_input(preset, &self.user_agent, cx),
            Some(self.strings.user_agent_desc.clone()),
            cx,
        )
    }

    fn render_cookie(&self, cx: &mut Context<Self>) -> Div {
        form_field(
            self.strings.cookie.clone(),
            Self::textarea(&self.cookie, px(72.), cx),
            Some(self.strings.cookie_desc.clone()),
            cx,
        )
    }

    fn render_checksum(&self, cx: &mut Context<Self>) -> Div {
        let options = HASH_ALGORITHMS
            .iter()
            .map(|algorithm| (*algorithm, SharedString::from(*algorithm), false))
            .collect();
        let preset = self.dropdown(
            "new-download-hash-algorithm",
            Some(PRESET_DROPDOWN_WIDTH),
            self.hash_algorithm,
            options,
            |this, algorithm, _, cx| {
                this.hash_algorithm = algorithm;
                cx.notify();
            },
            cx,
        );
        form_field(
            self.strings.checksum.clone(),
            Self::preset_with_input(preset, &self.checksum, cx),
            Some(self.strings.checksum_desc.clone()),
            cx,
        )
    }

    /// 自定义请求头：每行「名称 | 值 | 删除」，末尾「添加请求头」。
    fn render_headers(&self, cx: &mut Context<Self>) -> Div {
        let spacing = active_theme(cx).tokens().spacing;
        let mut rows = v_flex().w_full().gap(spacing.sm);
        for row in &self.headers {
            let row_id = row.id;
            rows = rows.child(
                h_flex()
                    .w_full()
                    .gap(spacing.sm)
                    .child(
                        div()
                            .flex_none()
                            .w(HEADER_NAME_WIDTH)
                            .child(Input::new(&row.key).control(cx).w_full()),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .child(Input::new(&row.value).control(cx).w_full()),
                    )
                    .child(
                        Button::new(SharedString::from(format!(
                            "new-download-header-remove-{row_id}"
                        )))
                        .ghost()
                        .icon(FluxIcon::X)
                        .control_icon(cx)
                        .on_click(cx.listener(
                            move |this, _: &ClickEvent, _, cx| {
                                this.headers.retain(|row| row.id != row_id);
                                cx.notify();
                            },
                        )),
                    ),
            );
        }
        rows = rows.child(
            h_flex().child(
                Button::new("new-download-header-add")
                    .outline()
                    .icon(FluxIcon::Plus)
                    .label(self.strings.add_header.clone())
                    .control(cx)
                    .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                        this.add_header_row(window, cx);
                    })),
            ),
        );
        form_field(
            self.strings.headers.clone(),
            rows,
            Some(self.strings.headers_desc.clone()),
            cx,
        )
    }

    /// 「高级」折叠区：标题行可点击展开；展开后字段同样走 `form_field`，开关进 `option_group`。
    fn render_advanced(&self, cx: &mut Context<Self>) -> Div {
        let theme = active_theme(cx);
        let tokens = theme.tokens().clone();
        let extended = theme.extended().clone();
        let open = self.advanced_open;
        let disclosure = h_flex()
            .id("new-download-advanced-toggle")
            .w_full()
            .gap(tokens.spacing.xs)
            .items_center()
            .cursor_pointer()
            .child(
                Icon::new(if open {
                    FluxIcon::ChevronDown
                } else {
                    FluxIcon::ChevronRight
                })
                .size(extended.icon.md)
                .text_color(extended.colors.text_tertiary),
            )
            .child(
                div()
                    .text_size(tokens.typography.sm.size)
                    .line_height(tokens.typography.sm.line_height)
                    .font_weight(FontWeight::MEDIUM)
                    .text_color(tokens.colors.foreground)
                    .child(self.strings.advanced.clone()),
            )
            .child(
                div()
                    .flex_1()
                    .h(px(1.))
                    .ml(tokens.spacing.sm)
                    .bg(extended.colors.hairline),
            )
            .on_click(cx.listener(|this, _: &ClickEvent, _, cx| {
                this.advanced_open = !this.advanced_open;
                cx.notify();
            }));
        let section = v_flex().w_full().gap(form_gap(cx)).child(disclosure);
        if !open {
            return section;
        }
        let show_auth = !self.is_batch() && !self.all_magnet();
        let mut options = Vec::with_capacity(2);
        if show_auth {
            options.push(self.toggle(
                "new-download-save-site-auth",
                self.strings.http_auth_save.clone(),
                None,
                self.save_site_auth,
                |this, checked| this.save_site_auth = checked,
                cx,
            ));
        }
        options.push(self.toggle(
            "new-download-ignore-tls",
            self.strings.ignore_tls.clone(),
            Some(self.strings.ignore_tls_desc.clone()),
            self.ignore_tls_errors,
            |this, checked| this.ignore_tls_errors = checked,
            cx,
        ));
        let mut fields = form(cx);
        if show_auth {
            fields = fields.child(self.render_http_auth(cx));
        }
        section.child(
            fields
                .child(self.render_proxy(cx))
                .child(self.render_user_agent(cx))
                .child(self.render_cookie(cx))
                .child(self.render_checksum(cx))
                .child(self.render_headers(cx))
                .child(option_group(options, cx)),
        )
    }

    fn render_form(&self, cx: &mut Context<Self>) -> Div {
        let tokens = active_theme(cx).tokens().clone();
        form(cx)
            .px(tokens.spacing.lg)
            .pt(tokens.spacing.xs)
            .pb(tokens.spacing.lg)
            .child(self.render_urls(cx))
            .child(self.render_save_dir(cx))
            .children(self.render_name_threads(cx))
            .child(self.render_advanced(cx))
    }

    fn render_footer(&self, cx: &mut Context<Self>) -> Div {
        let theme = active_theme(cx);
        let tokens = theme.tokens().clone();
        let enabled = self.can_submit(cx);
        let later_queue = self.queue_label(fluxdown_protocol::LATER_QUEUE_ID);
        let start_queue = self.queue_label(&self.context.queue_id);
        // 分体按钮：两半统一控件高度（`Size::Size` 让右侧箭头半成为 28×28 方块，
        // 文字取 `text_base` = 13px），左半主按钮再经 `control` 统一内边距。
        let split_size = Size::Size(CONTROL_HEIGHT);
        h_flex()
            .w_full()
            .flex_none()
            .px(tokens.spacing.lg)
            .py(tokens.spacing.md)
            .justify_end()
            .gap(tokens.spacing.sm)
            .bg(theme.extended().colors.chrome)
            .border_t_1()
            .border_color(theme.extended().colors.hairline)
            .child(
                Button::new("new-download-cancel")
                    .outline()
                    .label(self.strings.cancel.clone())
                    .control(cx)
                    .on_click(|_, window, _| window.remove_window()),
            )
            .child(
                DropdownButton::new("new-download-later")
                    .outline()
                    .with_size(split_size)
                    .disabled(!enabled)
                    .button(
                        Button::new("new-download-later-main")
                            .label(self.strings.download_later.clone())
                            .control(cx)
                            .tooltip(self.strings.format_later_tooltip(&later_queue))
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                this.submit(true, None, window, cx);
                            })),
                    )
                    .dropdown_menu_with_anchor(Anchor::TopRight, self.queue_menu(true, cx)),
            )
            .child(
                DropdownButton::new("new-download-start")
                    .primary()
                    .with_size(split_size)
                    .disabled(!enabled)
                    .button(
                        Button::new("new-download-start-main")
                            .label(self.strings.format_start(self.entries.len()))
                            .control(cx)
                            .tooltip(self.strings.format_start_tooltip(&start_queue))
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                this.submit(false, None, window, cx);
                            })),
                    )
                    .dropdown_menu_with_anchor(Anchor::TopRight, self.queue_menu(false, cx)),
            )
    }
}

impl Render for NewDownloadView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let tokens = active_theme(cx).tokens().clone();
        v_flex()
            .size_full()
            // 表单区用 surface（白），与设置窗口内容区一致。
            .bg(tokens.colors.surface)
            .child(self.render_header(cx))
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .child(self.render_form(cx).overflow_y_scrollbar()),
            )
            .child(self.render_footer(cx))
    }
}

/// 扩展名匹配（忽略大小写），文件选择器不支持按类型过滤时在结果上兜底。
fn has_extension(path: &std::path::Path, extensions: &[&str]) -> bool {
    path.extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| {
            extensions
                .iter()
                .any(|candidate| extension.eq_ignore_ascii_case(candidate))
        })
}
