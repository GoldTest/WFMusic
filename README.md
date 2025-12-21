# WFMusic

WFMusic 是一款基于 Kotlin 和 Compose Multiplatform 开发的桌面端聚合音乐播放器。它支持从多个主流音乐平台搜索并播放音乐，提供极致的跨平台桌面音乐体验。

## ✨ 项目特性

- **聚合搜索**：一键搜索网易云、QQ音乐、酷狗、酷我、咪咕、iTunes、Bilibili 等多个平台的音乐资源。
- **本地管理**：支持导入本地音乐文件，管理你的私人音乐库。
- **播放列表**：轻松创建、重命名和管理自定义播放列表。
- **歌词显示**：支持在线匹配并显示歌曲歌词。
- **封面渲染**：自动获取并展示精美的专辑封面。
- **跨平台支持**：基于 Compose Multiplatform，目前重点支持 Windows (Exe/Msi 格式安装包)。
- **现代 UI**：采用 Material 3 设计风格，支持深色模式。

## 🛠️ 技术栈

- **语言**：[Kotlin](https://kotlinlang.org/)
- **UI 框架**：[Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
- **网络请求**：[Ktor](https://ktor.io/)
- **图片加载**：[Kamel](https://github.com/Kamel-Media/Kamel)
- **音频解码**：[JavaFX Media](https://openjfx.io/javadoc/21/javafx.media/javafx/scene/media/package-summary.html) (支持 MP3, AAC/M4A, WAV 等)
- **序列化**：[Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)

## 🚀 快速开始

### 环境准备

- JDK 17 或更高版本
- Gradle 8.5 (项目中已包含 Gradle Wrapper)

### 本地运行

1. 克隆项目到本地：
   ```bash
   git clone https://github.com/GoldTest/WFMusic.git
   cd WFMusic
   ```

2. 运行应用：
   ```bash
   ./gradlew.bat run
   ```

### 打包发布

项目已配置 GitHub Actions 自动构建 Windows 安装包。你也可以在本地手动打包：

- **打包为 Exe**：`./gradlew.bat packageExe`
- **打包为 Msi**：`./gradlew.bat packageMsi`

生成的文件将位于 `build/compose/binaries/main/` 目录下。

## 📁 项目结构

```text
src/
├── main/
│   ├── kotlin/
│   │   └── com.workforboss/
│   │       ├── Main.kt              # 程序入口
│   │       └── music/
│   │           ├── sources/         # 音乐源适配器 (网易, QQ, 酷狗等)
│   │           ├── AudioPlayer.kt   # 音频播放核心逻辑
│   │           ├── MusicPlayerTab.kt# 播放器 UI 界面
│   │           └── Storage.kt       # 本地存储与持久化
│   └── resources/                   # 图标及静态资源
```

## 🤝 贡献指南

欢迎通过以下方式参与贡献：
- 提交 [Issue](https://github.com/GoldTest/WFMusic/issues) 报告 Bug 或提出新功能建议。
- 提交 Pull Request 改进代码或文档。

## 📄 开源协议

本项目遵循 [MIT License](LICENSE) 协议。

---

*注意：本项目仅供学习交流使用，音乐版权归各平台所有。*
