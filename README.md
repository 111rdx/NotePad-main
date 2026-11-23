# 青笺 NotePad：期中实验

在原 NotePad 示例的 Java + SQLite + ContentProvider 结构上完成实验扩展，无需服务器。

## 已实现功能

| 实验要求 | 实现 |
| --- | --- |
| 必做：时间戳 | 列表显示最后修改时间，格式为 `yyyy-MM-dd HH:mm`，按修改时间倒序排列 |
| 必做：笔记查询 | 顶部搜索框同时匹配标题和正文，输入即筛选；支持中文及 `%`、`_`、反斜线的字面量查询 |
| 扩展一：UI 美化 | 青绿色主题、卡片列表、正文摘要、数量提示、空状态、独立标题输入和舒适的编辑间距 |
| 扩展二：笔记导出 | 编辑页菜单“导出为 TXT”，通过系统文件选择器保存 UTF-8 文本，包含标题、修改时间与正文 |

保留新建、编辑、删除、复制和粘贴功能，修正原清单中的新建/粘贴/标题编辑入口。编辑页离开时自动保存；内容未变化时不刷新修改时间。删除需要确认。空白的新笔记退出时丢弃，已有笔记不会因清空内容而被自动删除。

## 使用方法

1. 点击底部“新建笔记”，填写标题和正文；标题留空时自动采用正文首行（最多 30 个字符）。
2. 点击“保存”或返回列表，查看正文摘要和更新时间。
3. 在顶部输入关键词，按标题或正文筛选；清空输入后恢复全部笔记。
4. 长按条目可打开、复制或删除；右上角菜单可将剪贴板内容粘贴为新笔记。
5. 打开笔记，在右上角菜单选择“导出为 TXT”，选择保存位置并确认。取消文件选择不会删除笔记。
6. “撤销未保存的修改”恢复到最近一次保存的标题和正文；自动保存后不能再撤销之前的版本。

## 实现位置

- `NotesList.java`：`CursorLoader` 后台查询、参数化 LIKE 搜索、游标绑定、时间格式化、列表交互。
- `NoteEditor.java`：标题与正文编辑、变化判断、生命周期保存、旋转恢复、TXT 导出。
- `NotePad.java` / `NotePadProvider.java`：沿用原有 `created`、`modified` 字段和 Provider 接口，本次不升级数据库，不清空已有数据。
- `TitleEditor.java`：兼容原标题编辑入口，仅实际修改标题时更新时间。
- `res/layout/notes_list.xml` / `noteslist_item.xml` / `note_editor.xml`：列表、条目、编辑页布局。
- `res/values/styles.xml` / `strings.xml`：统一主题和中文文案。
- `AndroidManifest.xml`：修正操作与页面的匹配关系。

时间按设备当前时区显示。搜索通过 `selectionArgs` 传入关键词，并转义 LIKE 通配符。`CursorLoader` 负责后台查询和游标生命周期，监听 Provider 的数据更新。导出使用 `ACTION_CREATE_DOCUMENT`，不申请广泛存储权限；选择位置后的文件写入在后台线程执行。

## 构建

- JDK 17
- Android SDK Platform 34、Build Tools 34.0.0
- Gradle 8.13、Android Gradle Plugin 8.1.0（沿用项目配置）
- Android 5.0 / API 21 或更高版本

在 Android Studio 中打开项目，确认 Gradle 使用 JDK 17，并让 `local.properties` 指向本机 Android SDK，然后同步并运行 `app`。

```powershell
.\gradlew.bat assembleDebug lintDebug
```

安装包：`app/build/outputs/apk/debug/app-debug.apk`。

构建插件可能提示 compileSdk 34 超出其已测试范围，这是原项目工具版本提示；应用仍需通过编译和静态检查。保留原有数据库升级逻辑，本次未触发升级；日后新增字段时应编写保留数据的迁移，不能直接使用原来的删表升级方式。

## 验证和演示

`ExperimentTest` 包含 7 项行为测试，覆盖标题/正文搜索、特殊字符、保存及时间语义、新建页重建不重复插入、中文多行 UTF-8 导出、复制粘贴、空白新笔记丢弃和正文首行生成标题。沿用原示例的 Android 测试框架，测试依赖无需另行下载。

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.android.notepad.ExperimentTest
```

建议演示流程：

1. 新建“移动开发实验”，正文写“今天学习 SQLite 查询”。
2. 新建另一条不含相同关键词的笔记。
3. 搜索“SQLite”，证明关键词仅在正文中也能命中；输入不存在的词验证空状态，然后清空搜索。
4. 编辑正文，返回列表展示修改时间；仅打开后返回，时间不变。
5. 编辑期间旋转屏幕，确认标题和正文保留、没有重复笔记。
6. 导出中文多行笔记，用文件应用打开 TXT 核对内容。
7. 长按复制，再粘贴为新笔记；验证删除确认和重启后的数据保留。

实验报告可按“需求分析 → 原项目结构 → 时间戳 → 搜索 → UI 美化 → 导出 → 测试结果”组织。运行截图应来自实际应用。

## 本次验证结果

- 应用和测试包均构建成功，`lintDebug` 为 0 错误；保留原项目的旧 API、图标密度等提示。
- 独立 Android API 37 模拟器上，`ExperimentTest` 的 7 项测试全部通过。
- TXT 测试拦截系统文件选择请求并返回测试保存位置，实际执行导出写入、核对 UTF-8 中文和换行；未自动操作第三方云盘。
- 修复新版 Android 的剪贴板授权与焦点限制，原有笔记数据结构未改动。
- 原始测试输出见 [验证记录](docs/test-results.txt)，界面见 [列表截图](docs/screenshots/notes-list.png)。
