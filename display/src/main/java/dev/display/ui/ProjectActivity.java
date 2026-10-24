package dev.display.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.*;
import dev.display.DisplayApplication;
import dev.display.core.CoreException;
import dev.display.core.ProjectCore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Scope → categories → primitive interface fields. All Core/PM work stays off the UI thread. */
public final class ProjectActivity extends Activity {
    private record AppRow(String pkg, String label, ApplicationInfo info, android.graphics.drawable.Drawable icon, JSONObject project) {}
    private interface Work { void run() throws Exception; }
    private interface Input { String text(); }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Input> inputs = new ArrayList<>();
    private final int[] positions = new int[3];
    private DisplayApplication display;
    private FrameLayout root;
    private LinearLayout screen, body, appList;
    private ScrollView scroll;
    private AlertDialog editor;
    private InterfaceCatalog.Spec editing;
    private int editingField;
    private Bundle restoredEditor;
    private int page, generation;
    private String pkg, category, appLabel, query = "";
    private JSONObject project;
    private List<AppRow> apps = List.of();
    private boolean active, writing;
    private int background, surface, foreground, secondary, accent, border;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        display = (DisplayApplication)getApplication();
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = Color.parseColor(dark ? "#111827" : "#F5F6FA");
        surface = Color.parseColor(dark ? "#1D2838" : "#FFFFFF");
        foreground = Color.parseColor(dark ? "#F4F7FC" : "#17212B");
        secondary = Color.parseColor(dark ? "#B3C0D2" : "#64748B");
        accent = Color.parseColor(dark ? "#8CAFFF" : "#246BFE");
        border = Color.parseColor(dark ? "#324052" : "#E3E8F0");
        if (state != null) {
            page = state.getInt("page"); pkg = state.getString("pkg"); category = state.getString("category");
            query = state.getString("query", "");
            int[] saved = state.getIntArray("positions"); if (saved != null) System.arraycopy(saved, 0, positions, 0, Math.min(3, saved.length));
            restoredEditor = state.getBundle("editor");
        }
        root = new FrameLayout(this); root.setBackgroundColor(background); setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom)); return insets;
        });
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    }
    @Override public void onResume() { super.onResume(); active = true; if (editor == null) refresh(); }
    @Override public void onStop() { active = false; generation++; rememberPosition(); super.onStop(); }
    @Override public void onDestroy() { if (editor != null) editor.dismiss(); worker.shutdown(); super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle state) {
        rememberPosition(); state.putInt("page", page); state.putString("pkg", pkg); state.putString("category", category);
        state.putString("query", query); state.putIntArray("positions", positions);
        if (editor != null && editor.isShowing() && editing != null && !writing) {
            Bundle edit = new Bundle(); edit.putString("key", editing.key()); edit.putInt("field", editingField);
            edit.putStringArrayList("values", rawInputs());
            edit.putLong("profileRevision", project.optLong("profileRevision"));
            edit.putLong("targetRevision", project.optLong("targetRevision")); state.putBundle("editor", edit);
        }
        super.onSaveInstanceState(state);
    }
    // API 33+ uses the native dispatcher registered above; this is only the API 31/32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { back(); }
    private void back() {
        if (writing) return;
        rememberPosition();
        if (page == 2) { page = 1; category = null; }
        else if (page == 1) { page = 0; pkg = null; }
        else { finish(); return; }
        refresh();
    }
    private void rememberPosition() { if (scroll != null) positions[page] = scroll.getScrollY(); }
    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private TextView text(String content, int sp, int color, boolean bold) {
        TextView text = new TextView(this); text.setText(content); text.setTextSize(sp); text.setTextColor(color);
        if (bold) text.setTypeface(null, Typeface.BOLD);
        text.setLineSpacing(dp(3), 1); return text;
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private GradientDrawable rounded(int color, int radius, boolean stroke) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (stroke) drawable.setStroke(dp(1), border); return drawable;
    }
    private void touchable(View view, Runnable action) {
        android.util.TypedValue value = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        view.setForeground(getDrawable(value.resourceId)); view.setClickable(true); view.setFocusable(true);
        view.setOnClickListener(v -> { if (!writing) action.run(); });
    }
    private Button button(String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextColor(accent); button.setMinHeight(dp(48));
        button.setBackground(rounded(surface, 12, true)); button.setStateListAnimator(null);
        if (title.equals("‹")) button.setContentDescription("返回上一级");
        button.setOnClickListener(v -> { if (!writing) action.run(); }); return button;
    }
    private void shell(String title, String subtitle) {
        root.removeAllViews(); screen = column(); root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout toolbar = row(); toolbar.setPadding(dp(16), dp(6), dp(12), dp(6));
        if (page > 0) toolbar.addView(button("‹", this::back), new LinearLayout.LayoutParams(dp(48), dp(52)));
        LinearLayout headings = column(); headings.addView(text(title, 23, foreground, true));
        if (!subtitle.isEmpty()) headings.addView(text(subtitle, 12, secondary, false));
        toolbar.addView(headings, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = button("刷新", () -> { rememberPosition(); refresh(); }); refresh.setContentDescription("刷新配置与作用域");
        toolbar.addView(refresh); screen.addView(toolbar);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(); body.setPadding(dp(20), dp(16), dp(20), dp(24)); scroll.addView(body);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void note(String title, String detail) {
        LinearLayout box = column(); box.setPadding(dp(16), dp(14), dp(16), dp(14)); box.setBackground(rounded(surface, 16, true));
        box.addView(text(title, 15, foreground, true)); TextView description = text(detail, 13, secondary, false);
        description.setPadding(0, dp(5), 0, 0); box.addView(description); addBlock(box, 16);
    }
    private void addBlock(View view, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(margin); body.addView(view, lp);
    }
    private void refresh() {
        if (!active || writing) return;
        int ticket = ++generation, wantedPage = page; String wantedPackage = pkg;
        shell(page == 0 ? "Display" : "接口配置", "正在读取…");
        ProgressBar progress = new ProgressBar(this); body.addView(progress, new LinearLayout.LayoutParams(-1, dp(64)));
        worker.execute(() -> {
            try {
                if (wantedPage == 0) {
                    List<AppRow> rows = new ArrayList<>(); ProjectCore core = display.core();
                    for (String name : display.framework.packages()) {
                        ApplicationInfo info = null; android.graphics.drawable.Drawable icon = null; String label = name;
                        try {
                            info = getPackageManager().getApplicationInfo(name, 0);
                            label = getPackageManager().getApplicationLabel(info).toString(); icon = getPackageManager().getApplicationIcon(info);
                        }
                        catch (PackageManager.NameNotFoundException ignored) { /* stale scope entry stays visible */ }
                        rows.add(new AppRow(name, label, info, icon, core.getProject(name)));
                    }
                    Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
                    rows.sort((a, b) -> collator.compare(a.label, b.label));
                    main.post(() -> { if (valid(ticket)) { apps = rows; renderApps(); } });
                } else {
                    if (!display.framework.contains(wantedPackage)) throw new CoreException("NOT_IN_SCOPE", "应用已移出 Display 作用域，请返回应用列表");
                    JSONObject state = display.core().getProject(wantedPackage);
                    String label = wantedPackage;
                    try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(wantedPackage, 0)).toString(); }
                    catch (PackageManager.NameNotFoundException ignored) {}
                    String finalLabel = label;
                    main.post(() -> { if (valid(ticket)) { project = state; appLabel = finalLabel; renderProject(); restoreEditor(); } });
                }
            } catch (Exception e) { main.post(() -> { if (valid(ticket)) showError(e); }); }
        });
    }
    private boolean valid(int ticket) { return active && !isDestroyed() && generation == ticket; }
    private String message(Exception e) {
        if (e instanceof CoreException c) return switch (c.code) {
            case "REVISION_CONFLICT" -> "配置已被其他入口修改，请刷新后重试";
            case "FRAMEWORK_UNAVAILABLE", "NOT_IN_SCOPE", "PROFILE_IN_USE" -> c.getMessage();
            case "ENTRY_LIMIT", "STORAGE_LIMIT" -> "保存空间已满，请先清理不需要的配置";
            default -> "操作未完成，请刷新并检查配置";
        };
        if (e instanceof IllegalArgumentException) return e.getMessage();
        return "暂时无法读取或保存配置，请刷新重试";
    }
    private void showError(Exception e) {
        shell(page == 0 ? "Display" : "接口配置", "暂时不可用");
        note("无法加载配置", message(e));
        body.addView(button("重新加载", this::refresh));
        if (page > 0) body.addView(button("返回应用列表", () -> { page = 0; pkg = null; category = null; refresh(); }));
    }
    private void renderApps() {
        shell("Display", "作用域应用 · " + apps.size() + " 个");
        note("按应用改写接口", "选择应用配置接口值，开启开关后生效。关闭开关保留设置，并恢复后续调用的原值。");
        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(15); search.setTextColor(foreground);
        search.setHintTextColor(secondary); search.setHint("搜索应用名称或包名"); search.setContentDescription("搜索作用域应用");
        search.setInputType(InputType.TYPE_CLASS_TEXT); search.setText(query); addBlock(search, 16);
        appList = column(); addBlock(appList, 0); filterApps();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); filterApps(); }
            public void afterTextChanged(Editable s) {}
        });
        TextView help = text("作用域在模块管理器中选择，修改后回到此处刷新。", 12, secondary, false); help.setPadding(0, dp(18), 0, 0); body.addView(help);
        restorePosition();
    }
    private void filterApps() {
        appList.removeAllViews(); String needle = query.trim().toLowerCase(Locale.ROOT); int count = 0;
        for (AppRow app : apps) {
            if (!needle.isEmpty() && !(app.label + " " + app.pkg).toLowerCase(Locale.ROOT).contains(needle)) continue;
            count++;
            LinearLayout card = row(); card.setPadding(dp(14), dp(16), dp(10), dp(16)); card.setBackground(rounded(surface, 18, false));
            ImageView icon = new ImageView(this); if (app.icon != null) icon.setImageDrawable(app.icon);
            else icon.setImageResource(android.R.drawable.sym_def_app_icon);
            card.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout labels = column(); labels.setPadding(dp(14), 0, dp(6), 0);
            labels.addView(text(app.label, 17, foreground, true)); labels.addView(text(app.pkg, 11, secondary, false));
            int rules = app.project.optJSONArray("rules").length(); boolean enabled = app.project.optBoolean("enabled");
            labels.addView(text((enabled ? "已开启" : "已关闭") + " · " + (rules == 0 ? "未设置接口" : rules + " 组接口已保存"), 12, enabled ? accent : secondary, false));
            card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = appSwitch(app.project, app.pkg, app.label); toggle.setEnabled(app.info != null); card.addView(toggle);
            touchable(card, () -> openApp(app.pkg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(12); appList.addView(card, lp);
        }
        if (count == 0) {
            TextView empty = text(apps.isEmpty() ? "尚无作用域应用\n在模块管理器中启用 Display 并选择应用，然后刷新。" : "没有匹配的应用", 15, secondary, false);
            empty.setPadding(dp(8), dp(28), dp(8), dp(28)); appList.addView(empty);
        }
    }
    private void openApp(String name) { rememberPosition(); pkg = name; page = 1; category = null; positions[1] = positions[2] = 0; refresh(); }
    private Switch appSwitch(JSONObject state, String name, String label) {
        Switch toggle = new Switch(this); toggle.setShowText(false); toggle.setMinHeight(dp(48)); toggle.setMinWidth(dp(54));
        toggle.setContentDescription(label + "应用开关"); boolean enabled = state.optBoolean("enabled"); toggle.setChecked(enabled);
        toggle.setOnClickListener(v -> {
            if (writing) { toggle.setChecked(enabled); return; }
            boolean requested = toggle.isChecked(); JSONArray rules = state.optJSONArray("rules");
            if (requested && rules.length() == 0) { toggle.setChecked(false); toast("请先设置至少一个接口值，再开启应用"); openApp(name); return; }
            persist(name, state, rules, requested, null);
        });
        return toggle;
    }
    private void renderProject() {
        String heading = appLabel;
        if (page == 2) for (InterfaceCatalog.Category item : InterfaceCatalog.CATEGORIES) if (item.id().equals(category)) heading = item.title();
        shell(heading, page == 1 ? "系统框架接口" : appLabel);
        LinearLayout stateRow = row(); stateRow.setPadding(dp(16), dp(12), dp(12), dp(12)); stateRow.setBackground(rounded(surface, 16, true));
        LinearLayout labels = column(); labels.addView(text("应用改写", 16, foreground, true));
        labels.addView(text(project.optBoolean("enabled") ? "已开启 · 修改后会更新配置" : "已关闭 · 可先保存接口值", 12, secondary, false));
        stateRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); stateRow.addView(appSwitch(project, pkg, appLabel)); addBlock(stateRow, 20);
        if (page == 1) renderCategories(); else renderInterfaces();
        restorePosition();
    }
    private JSONArray rules() { return project.optJSONArray("rules"); }
    private void renderCategories() {
        for (InterfaceCatalog.Category item : InterfaceCatalog.CATEGORIES) {
            int total = 0, configured = 0;
            for (InterfaceCatalog.Spec spec : InterfaceCatalog.SPECS) if (spec.category().equals(item.id())) {
                total += spec.fields().size(); if (InterfaceCatalog.value(rules(), spec.key()) != null) configured += spec.fields().size();
            }
            LinearLayout card = row(); card.setPadding(dp(18), dp(18), dp(16), dp(18)); card.setBackground(rounded(surface, 16, false));
            LinearLayout labels = column(); labels.addView(text(item.title(), 18, foreground, true)); labels.addView(text(item.description(), 12, secondary, false));
            labels.addView(text("已设置 " + configured + " / " + total + " 项", 12, configured > 0 ? accent : secondary, false));
            card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(text("›", 26, secondary, false));
            touchable(card, () -> { rememberPosition(); category = item.id(); page = 2; positions[2] = 0; renderProject(); }); addBlock(card, 12);
        }
    }
    private void renderInterfaces() {
        TextView hint = text("点击接口填写基础类型值；未设置的接口保持原值。", 13, secondary, false); addBlock(hint, 16);
        for (InterfaceCatalog.Spec spec : InterfaceCatalog.SPECS) if (spec.category().equals(category)) {
            List<String> current;
            try { current = InterfaceCatalog.values(spec, InterfaceCatalog.value(rules(), spec.key())); }
            catch (Exception e) { current = List.of(); }
            for (int i = 0; i < spec.fields().size(); i++) {
                InterfaceCatalog.Field field = spec.fields().get(i); int index = i;
                LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(rounded(surface, 16, false));
                LinearLayout heading = row(); heading.addView(text(field.title(), 16, foreground, true), new LinearLayout.LayoutParams(0, -2, 1));
                TextView badge = text(field.type(), 11, accent, true); badge.setPadding(dp(8), dp(3), dp(8), dp(3)); badge.setBackground(rounded(background, 8, false)); heading.addView(badge); card.addView(heading);
                TextView api = text(field.api(), 11, secondary, false); api.setPadding(0, dp(6), 0, dp(8)); card.addView(api);
                String value = i < current.size() ? current.get(i) : null;
                if (spec.languages() && !current.isEmpty()) value = String.join(" → ", current);
                card.addView(text(value == null ? "保持原值" : value, 15, value == null ? secondary : accent, false));
                touchable(card, () -> showEditor(spec, index, null)); addBlock(card, 10);
            }
        }
    }
    private void restorePosition() { int y = positions[page]; scroll.post(() -> scroll.scrollTo(0, y)); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private ArrayList<String> rawInputs() { ArrayList<String> values = new ArrayList<>(); for (Input input : inputs) values.add(input.text()); return values; }
    private void restoreEditor() {
        if (restoredEditor == null) return; Bundle saved = restoredEditor; restoredEditor = null;
        if (saved.getLong("profileRevision") != project.optLong("profileRevision") || saved.getLong("targetRevision") != project.optLong("targetRevision")) {
            toast("配置已更新，请重新打开编辑器"); return;
        }
        try { showEditor(InterfaceCatalog.find(saved.getString("key")), saved.getInt("field"), saved.getStringArrayList("values")); }
        catch (Exception ignored) { toast("请重新选择要编辑的接口"); }
    }
    private void showEditor(InterfaceCatalog.Spec spec, int selectedField, List<String> restored) {
        if (writing) return; editing = spec; editingField = selectedField; inputs.clear();
        JSONObject expected = project; String editedPackage = pkg;
        List<String> current;
        try { current = restored != null ? restored : InterfaceCatalog.values(spec, InterfaceCatalog.value(rules(), spec.key())); }
        catch (Exception e) { toast("无法读取此接口值，请刷新"); return; }
        LinearLayout form = column(); form.setPadding(dp(24), dp(8), dp(24), dp(12));
        if (spec.fields().size() > 1) form.addView(text("此组接口一同保存，请填写下列基础数值。", 13, secondary, false));
        if (spec.key().startsWith("time.wall")) form.addView(text("固定时间与时间偏移互斥，保存后会替换另一种模式。", 13, secondary, false));
        List<View> focusable = new ArrayList<>();
        if (spec.languages()) {
            LinearLayout languages = column(); form.addView(languages);
            int count = Math.max(1, current.size());
            for (int i = 0; i < count; i++) addInput(languages, spec.fields().get(0), i < current.size() ? current.get(i) : "", i == 0 ? "首选语言" : "备选语言 " + i, spec, focusable);
            Button add = button("＋ 添加语言", () -> {
                if (inputs.size() >= 8) { toast("最多设置 8 个语言标签"); return; }
                addInput(languages, spec.fields().get(0), "", "备选语言 " + inputs.size(), spec, focusable);
            }); form.addView(add); form.addView(text("按顺序使用，空白项会忽略。", 12, secondary, false));
        } else for (int i = 0; i < spec.fields().size(); i++) addInput(form, spec.fields().get(i), i < current.size() ? current.get(i) : "", null, spec, focusable);
        TextView error = text("", 13, Color.rgb(190, 55, 55), false); error.setPadding(0, dp(8), 0, 0); form.addView(error);
        ScrollView container = new ScrollView(this); container.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(spec.fields().get(selectedField).title()).setView(container)
                .setNegativeButton("取消", null).setPositiveButton("保存", null)
                .setNeutralButton(spec.fields().size() > 1 ? "恢复此组原值" : "恢复原值", null).create();
        editor = dialog; dialog.setOnDismissListener(d -> { if (editor == dialog) { editor = null; editing = null; inputs.clear(); } });
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(InterfaceCatalog.value(expected.optJSONArray("rules"), spec.key()) != null);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (writing) return;
                try {
                    Object value = InterfaceCatalog.parse(spec, rawInputs());
                    JSONArray next = InterfaceCatalog.replace(expected.getJSONArray("rules"), spec.key(), value);
                    persist(editedPackage, expected, next, expected.getBoolean("enabled"), dialog);
                } catch (Exception e) { error.setText(message(e)); }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (writing) return;
                try {
                    JSONArray next = InterfaceCatalog.replace(expected.getJSONArray("rules"), spec.key(), null);
                    persist(editedPackage, expected, next, expected.getBoolean("enabled") && next.length() > 0, dialog);
                } catch (Exception e) { error.setText(message(e)); }
            });
            if (!focusable.isEmpty()) { View focus = focusable.get(Math.min(selectedField, focusable.size() - 1)); focus.requestFocus(); }
        });
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }
    private void addInput(LinearLayout form, InterfaceCatalog.Field field, String current, String title, InterfaceCatalog.Spec spec, List<View> focusable) {
        TextView label = text((title == null ? field.title() : title) + " · " + field.type(), 13, secondary, false);
        label.setPadding(0, dp(16), 0, dp(4)); form.addView(label);
        if (field.kind() == InterfaceCatalog.Kind.BOOLEAN || field.kind() == InterfaceCatalog.Kind.CHOICE) {
            String[] values = field.kind() == InterfaceCatalog.Kind.BOOLEAN ? new String[]{"true", "false"} : spec.choices();
            String[] labels = field.kind() == InterfaceCatalog.Kind.BOOLEAN ? new String[]{"true · 是", "false · 否"} : spec.choiceLabels();
            Spinner spinner = new Spinner(this); List<String> options = new ArrayList<>(); options.add("请选择"); options.addAll(List.of(labels));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter); spinner.setMinimumHeight(dp(48)); spinner.setContentDescription(field.title() + "返回值");
            for (int i = 0; i < values.length; i++) if (values[i].equals(current)) spinner.setSelection(i + 1);
            form.addView(spinner); focusable.add(spinner); inputs.add(() -> spinner.getSelectedItemPosition() == 0 ? "" : values[spinner.getSelectedItemPosition() - 1]);
        } else {
            EditText input = new EditText(this); input.setTextSize(16); input.setTextColor(foreground); input.setHintTextColor(secondary); input.setSingleLine(true);
            input.setInputType(field.kind() == InterfaceCatalog.Kind.TEXT ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | (field.kind() == InterfaceCatalog.Kind.NUMBER ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(field.kind() == InterfaceCatalog.Kind.TEXT ? 128 : 40)});
            input.setHint(field.hint()); input.setText(current); input.setSelectAllOnFocus(true); input.setContentDescription((title == null ? field.title() : title) + "返回值");
            form.addView(input, new LinearLayout.LayoutParams(-1, dp(52))); focusable.add(input); inputs.add(() -> input.getText().toString());
        }
        if (!field.hint().isEmpty()) form.addView(text(field.hint(), 11, secondary, false));
    }
    private void persist(String name, JSONObject expected, JSONArray rules, boolean enabled, AlertDialog dialog) {
        if (writing) return; rememberPosition(); writing = true; ++generation;
        String request = "ui-" + UUID.randomUUID();
        if (dialog != null) { dialog.setCancelable(false); for (int id : new int[]{-1, -2, -3}) dialog.getButton(id).setEnabled(false); }
        FrameLayout overlay = new FrameLayout(this); overlay.setBackgroundColor(0x88777777); overlay.setClickable(true); overlay.setFocusable(true);
        ProgressBar progress = new ProgressBar(this); FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER); overlay.addView(progress, lp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        worker.execute(() -> {
            Exception failure = null;
            try { display.core().saveProject(name, rules, expected.getLong("profileRevision"), expected.getLong("targetRevision"), enabled, request); }
            catch (Exception e) { failure = e; }
            Exception outcome = failure;
            main.post(() -> {
                writing = false; if (isDestroyed()) return; root.removeView(overlay);
                if (dialog != null) dialog.dismiss();
                if (active) {
                    toast(outcome == null ? (enabled ? "已保存并开启，应用缓存值可能需要刷新" : "已保存 · 应用改写已关闭") : message(outcome));
                    refresh();
                }
            });
        });
    }
}
