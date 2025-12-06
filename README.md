# ClipCode

**The ultimate code sharing tool for IntelliJ IDEA** - Quickly copy file contents with smart formatting, perfect for sharing with AI assistants like ChatGPT, Claude, and Gemini.

## Features

- **Copy Files & Directories**: Copy multiple files with customizable headers and formatting
- **Git Integration**: Copy from staging area, changes view, and commit history with change type labels
- **Paste & Restore**: Recreate files from copied content with automatic directory creation
- **Advanced Filtering**: Include/exclude paths and file patterns
- **Smart Statistics**: File count, lines, words, and token estimates

## Installation

### From JetBrains Marketplace
1. Open **Settings** > **Plugins** > **Marketplace**
2. Search for "ClipCode"
3. Click **Install**

### Manual Installation
Download the [latest release](https://github.com/audichuang/ClipCode/releases/latest) and install via **Settings** > **Plugins** > **Install Plugin from Disk...**

## Usage

### Copy Files
1. Select files/folders in Project view
2. Right-click > **ClipCode: Copy to Clipboard**

### Copy from Git
1. Open Git tool window (Commit, Changes, or Log view)
2. Select changed files
3. Right-click > **ClipCode: Copy Full Source**

### Paste & Restore Files
1. Copy content using ClipCode from any source
2. Right-click in Project view > **ClipCode: Paste and Restore Files**
3. Or use keyboard shortcut: `Ctrl+Shift+Alt+V`

### Settings
**Settings** > **ClipCode Settings**
- Customize header format with `$FILE_PATH` placeholder
- Add pre/post text wrappers
- Configure file limits and filters

## Development

### Build
```bash
./gradlew build
```

### Test in IDE
```bash
./gradlew runIde
```

## Compatibility

- IntelliJ IDEA 2024.3 - 2025.2
- All JetBrains IDEs (WebStorm, PyCharm, Rider, etc.)

## Author

**audichuang** - [GitHub](https://github.com/audichuang)

## License

This project is licensed under the MIT License.

---
Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
