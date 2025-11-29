# 🧪 Complete Testing Guide - Copy File Content Plugin

This guide shows how to test **all** plugin functionalities locally, including features from v1.0.0 and v1.0.1.

---

## 🚀 Step 1: Launch the Plugin in Sandbox IDE

### Start the Development IDE

```bash
./gradlew runIde
```

**What happens:**
- Gradle downloads IntelliJ IDEA Community Edition (if not cached)
- Launches a sandboxed IntelliJ IDE with your plugin installed
- Takes ~30 seconds to 2 minutes on first run (faster on subsequent runs)
- Watch for: `BUILD SUCCESSFUL` and IDE window opening

**Note:** The sandbox IDE runs isolated from your main IntelliJ installation, so it's safe to experiment.

---

## 📋 Step 2: Create a Test Project in Sandbox

Once the sandbox IDE opens:

1. **Create New Project**
   - File → New → Project
   - Select "Empty Project" or "Java" (any type works)
   - Name: `plugin-test`
   - Click "Create"

2. **Create Test Files**
   - Right-click on project root → New → Directory → `src`
   - Inside `src`, create these files:
     - `Main.java` (add some code)
     - `Config.properties` (add some properties)
     - `README.md` (add some text)
   - Create a subdirectory: `src/utils`
     - Add: `Helper.java`

Your structure should look like:
```
plugin-test/
├── src/
│   ├── Main.java
│   ├── Config.properties
│   ├── README.md
│   └── utils/
│       └── Helper.java
```

---

## ✅ Step 3: Test Core Feature - Copy from Project Tree

### Test 3.1: Copy Single File

1. **Select file** in Project tree (left sidebar)
   - Click on `Main.java`

2. **Right-click** → Find "**Copy File Content to Clipboard**"
   - Should appear after "Copy Path" in the context menu

3. **Verify**:
   - Open any text editor (Notepad, VS Code, etc.)
   - Paste (Ctrl+V / Cmd+V)
   - ✅ You should see:
     ```
     // file: src/Main.java
     [your Java code here]
     ```

4. **Check notification** (bottom-right corner):
   - Should show "1 file copied"
   - Click notification to see statistics:
     - Total characters, lines, words, estimated tokens

### Test 3.2: Copy Multiple Files

1. **Multi-select files**:
   - Hold `Ctrl` (or `Cmd` on Mac)
   - Click `Main.java`, `Config.properties`, `README.md`

2. **Right-click** → "Copy File Content to Clipboard"

3. **Verify**:
   - Paste into text editor
   - ✅ Should see all 3 files with headers:
     ```
     // file: src/Main.java
     [Java code]

     // file: src/Config.properties
     [properties]

     // file: src/README.md
     [markdown]
     ```

### Test 3.3: Copy Entire Directory

1. **Right-click on `src` folder** → "Copy File Content to Clipboard"

2. **Verify**:
   - Paste into text editor
   - ✅ Should see all files in `src/` and `src/utils/`
   - Files appear in order with paths

---

## 🆕 Step 4: Test Feature - Copy All Open Tabs

### Test 4.1: Open Multiple Files

1. **Open files in editor**:
   - Double-click `Main.java` (opens in editor)
   - Double-click `Helper.java`
   - Double-click `README.md`

2. **Verify** you have 3 tabs open at the top

### Test 4.2: Copy All Open Tabs

1. **Right-click on any editor tab** (at the top)
   - Look for "**Copy All Open Tabs Content to Clipboard**"

2. **Click it**

3. **Verify**:
   - Paste into text editor
   - ✅ Should see content from all 3 open files
   - Order matches tab order (left to right)

### Test 4.3: Test with No Tabs Open

1. **Close all tabs** (click X on each tab)
2. **Right-click in tab bar** → "Copy All Open Tabs Content to Clipboard"
3. **Verify**: Should see notification "No open tabs found to copy"

---

## 🆕 Step 5: Test Git Features - Copy from VCS/Git Windows

### Test 5.1: Initialize Git

1. **Enable Git**:
   - VCS → Enable Version Control Integration
   - Select "Git" → OK

2. **Make changes**:
   - Open `Main.java` in editor
   - Add a line: `// TODO: implement`
   - Save file (Ctrl+S / Cmd+S)

### Test 5.2: Copy from Git Changes View

1. **Open Git/Changes window**:
   - Click "Commit" tab (left sidebar) or
   - Alt+0 / Cmd+0 to toggle

2. **Find changed file** in "Changes" section
   - Should see `Main.java` listed

3. **Right-click on the file** in Changes view
   - Look for "**Copy File Content (Full Source)**"

4. **Click it**

5. **Verify**:
   - Paste into text editor
   - ✅ Should see: `// file: [MODIFIED] src/Main.java`
   - Content shows current file content with change type label

### Test 5.3: Copy from Git Staging Area (v1.0.1)

1. **Enable Git Staging** (if not already):
   - Settings → Version Control → Git → Enable staging area

2. **Make multiple changes**:
   - Modify `Main.java`, `README.md`
   - Create a new file `NewFile.java`

3. **In Commit window**, you'll see:
   - Unstaged section
   - Staged section

4. **Stage some files** (drag to Staged or click checkbox)

5. **Test in Staged section**:
   - Right-click on staged file → "**Copy File Content (Full Source)**"
   - ✅ Should copy with `[MODIFIED]` or `[NEW]` label

6. **Test in Unstaged section**:
   - Right-click on unstaged file → "**Copy File Content (Full Source)**"
   - ✅ Should also work

### Test 5.4: Copy from Git Log Window

1. **Open Git Log**:
   - VCS → Git → Show History
   - Or: Alt+9 / Cmd+9

2. **Select a commit** (click on any commit)

3. **In the commit details**, find changed files

4. **Right-click on a file** → "**Copy File Content (Full Source)**"

5. **Verify**:
   - ✅ Should show change type label and file content

### Test 5.5: Copy Multiple Changed Files

1. **In Commit/Changes window**:
   - Select multiple files (Ctrl+Click)
   - Right-click → "Copy File Content (Full Source)"

2. **Verify**: All files copied with their respective labels:
   ```
   // file: [NEW] src/NewFile.java
   [content]

   // file: [MODIFIED] src/Main.java
   [content]
   ```

---

## 🆕 Step 6: Test Paste and Restore Files (v1.0.0)

This is a **major new feature** - restores files from clipboard content!

### Test 6.1: Copy and Restore Single File

1. **Copy a file**:
   - Right-click `Main.java` in Project tree
   - "Copy File Content to Clipboard"

2. **Delete the file** (for testing):
   - Right-click `Main.java` → Delete → OK

3. **Paste and Restore**:
   - Right-click on project root
   - Find "**Paste and Restore Files from Clipboard**"
   - Or press: `Ctrl+Shift+Alt+V`

4. **Confirmation dialog**:
   - ✅ Should show: "Found 1 file(s) to restore: • src/Main.java"
   - Click "Yes"

5. **Verify**:
   - ✅ File `Main.java` is recreated
   - ✅ Content matches original
   - ✅ Directory structure created automatically

### Test 6.2: Restore Multiple Files with Directories

1. **Copy multiple files**:
   - Select `Main.java`, `utils/Helper.java`, `README.md`
   - Right-click → "Copy File Content to Clipboard"

2. **Delete all selected files**

3. **Paste and Restore**:
   - Right-click on project root
   - "Paste and Restore Files from Clipboard"

4. **Verify**:
   - ✅ All 3 files restored
   - ✅ Directory `utils/` created automatically
   - ✅ Files in correct locations

### Test 6.3: Restore with Git Change Labels (v1.0.1)

1. **Copy files from Git Changes**:
   - Make changes to files
   - In Git Changes window, select files
   - Right-click → "Copy File Content (Full Source)"
   - Content will have labels like `[MODIFIED]`

2. **Paste and Restore**:
   - Right-click on project → "Paste and Restore Files from Clipboard"

3. **Verify**:
   - ✅ Labels are automatically stripped
   - ✅ Files created with correct paths
   - ✅ `[DELETED]` files are skipped with notification

### Test 6.4: Overwrite Protection

1. **Copy a file** (e.g., `Main.java`)

2. **Paste and Restore** (file already exists):
   - Right-click → "Paste and Restore Files from Clipboard"

3. **Overwrite confirmation**:
   - ✅ Dialog asks: "File exists: src/Main.java. Overwrite?"
   - Choose "Yes" or "No to All"

4. **Verify** behavior matches choice

---

## 🆕 Step 7: Test Advanced Filtering System (v1.0.0)

### Test 7.1: Open Settings

1. **Open Settings**:
   - File → Settings (Windows/Linux) or
   - IntelliJ IDEA → Preferences (Mac)

2. **Navigate to**:
   - Tools → Copy File Content Settings

### Test 7.2: Test PATH Filters (Include)

1. **In Settings**, find "Filter Rules" table

2. **Click "Include Path"**:
   - File chooser opens
   - Select `src/utils/` directory
   - Click OK

3. **Table shows**:
   - Enabled: ✓
   - Type: PATH
   - Action: INCLUDE
   - Value: `src/utils`

4. **Enable filters**:
   - Check "Use Include Filters"
   - Click "Apply"

5. **Copy entire project**:
   - Right-click on project root → "Copy File Content to Clipboard"

6. **Verify**:
   - ✅ Only files in `src/utils/` are copied
   - ✅ Files outside `src/utils/` are skipped

### Test 7.3: Test PATTERN Filters (Exclude)

1. **Click "Exclude Pattern"**:
   - Input dialog appears
   - Enter: `*.md`
   - Click OK

2. **Table shows**:
   - Type: PATTERN
   - Action: EXCLUDE
   - Value: `*.md`

3. **Enable**:
   - Check "Use Exclude Filters"
   - Click "Apply"

4. **Copy files including README.md**

5. **Verify**:
   - ✅ `README.md` is skipped
   - ✅ `.java` files are copied

### Test 7.4: Test Individual Rule Toggle

1. **In filter table**, click checkbox in "Enabled" column

2. **Copy files**

3. **Verify**:
   - ✅ Disabled rules are not applied
   - ✅ Only enabled rules affect copy

### Test 7.5: Test Multiple Rules

1. **Create complex filter**:
   - Include PATH: `src/`
   - Exclude PATTERN: `*.properties`
   - Exclude PATTERN: `Test*.java`

2. **Copy entire project**

3. **Verify**:
   - ✅ Only files in `src/`
   - ✅ No `.properties` files
   - ✅ No files starting with `Test`

---

## ⚙️ Step 8: Test Other Settings

### Test 8.1: Test Header Format

1. **Change header format**:
   - Default: `// file: $FILE_PATH`
   - Change to: `### $FILE_PATH ###`
   - Click "Apply"

2. **Copy a file**

3. **Verify**:
   - ✅ Header now shows `### src/Main.java ###`

### Test 8.2: Test Pre/Post Text

1. **In Settings**:
   - Pre-text: `=== START OF FILES ===`
   - Post-text: `=== END OF FILES ===`
   - Click "Apply"

2. **Copy multiple files**

3. **Verify**:
   - ✅ Output starts with `=== START OF FILES ===`
   - ✅ Output ends with `=== END OF FILES ===`

### Test 8.3: Test File Count Limit

1. **Create many files**:
   - Create 5+ files in your test project

2. **In Settings**:
   - Enable "Set maximum file count"
   - Set limit to `2`
   - Click "Apply"

3. **Try to copy 5 files**

4. **Verify**:
   - ✅ Only 2 files copied
   - ✅ Warning notification: "File Limit Reached"

### Test 8.4: Test File Size Limit

1. **In Settings**:
   - Set "Maximum file size (KB)": `10`

2. **Create large file** > 10KB

3. **Try to copy it**

4. **Verify**:
   - ✅ File is skipped
   - ✅ Notification or log message

---

## 🔍 Step 9: Advanced Testing Scenarios

### Test 9.1: External Library / JAR Files

1. **Add external library to project**:
   - Add a `.jar` file to project dependencies

2. **Try to copy from external library**:
   - Expand External Libraries in Project view
   - Try to copy a file from JAR

3. **Verify**:
   - ✅ Path is cleaned and readable
   - ✅ No crash or error

### Test 9.2: Binary File Handling

1. **Add binary file**:
   - Copy an image (`.png`, `.jpg`) to project

2. **Try to copy it**

3. **Verify**:
   - ✅ Binary files are skipped automatically

### Test 9.3: Empty File

1. **Create empty file**: `Empty.txt` (no content)

2. **Copy it**

3. **Verify**:
   - ✅ Header appears: `// file: Empty.txt`
   - ✅ Content section is empty (no crash)

### Test 9.4: Special Characters in Path

1. **Create file with spaces**: `My File.java`

2. **Copy it**

3. **Verify**:
   - ✅ Path correctly shows: `// file: src/My File.java`

### Test 9.5: Multiple Change Type Labels (v1.0.1 Bug Fix)

1. **Manually create clipboard content** with multiple labels:
   ```
   // file: [MODIFIED] [NEW] src/Test.java
   public class Test {}
   ```

2. **Paste and Restore**

3. **Verify**:
   - ✅ Both labels stripped correctly
   - ✅ File created at `src/Test.java`
   - ✅ No `[MODIFIED]` or `[NEW]` in path

---

## 🐛 Troubleshooting

### Issue: Context menu option not appearing

**Solution:**
- Restart the sandbox IDE (close and run `./gradlew runIde` again)
- Check you're right-clicking on a file/folder, not empty space

### Issue: Git Staging Area action not visible

**Solution:**
- Enable Git Staging: Settings → Version Control → Git → Enable staging area
- Make sure you're in Git Staging UI mode
- Restart sandbox IDE

### Issue: Paste and Restore not working

**Solution:**
- Make sure clipboard contains content copied by this plugin
- Check content has proper file headers
- Look for error messages in Event Log

### Issue: Filters not applying

**Solution:**
- Check "Use Include Filters" or "Use Exclude Filters" is enabled
- Verify rule is enabled (checkbox in table)
- Check path/pattern syntax

---

## 📊 Complete Testing Checklist

**Core Features:**
- [ ] Copy single file from Project tree
- [ ] Copy multiple files from Project tree
- [ ] Copy entire directory recursively
- [ ] Statistics notification appears

**Copy All Open Tabs:**
- [ ] Copies content from all open editor tabs
- [ ] Correct order (left to right)
- [ ] Handles "no tabs open" gracefully

**Git Integration (v1.0.1):**
- [ ] Copy from Git Changes view with labels
- [ ] Copy from Git Staging Area (Staged section)
- [ ] Copy from Git Staging Area (Unstaged section)
- [ ] Copy from Git Log window
- [ ] Labels appear correctly: [NEW], [MODIFIED], [DELETED], [MOVED]

**Paste and Restore (v1.0.0):**
- [ ] Restore single file from clipboard
- [ ] Restore multiple files with directories
- [ ] Automatic directory creation
- [ ] Overwrite protection dialog
- [ ] Works with Git change labels
- [ ] Skips [DELETED] files

**Advanced Filtering (v1.0.0):**
- [ ] PATH include filters work
- [ ] PATH exclude filters work
- [ ] PATTERN include filters work
- [ ] PATTERN exclude filters work
- [ ] Individual rule enable/disable
- [ ] Master switches (Use Include/Exclude Filters)
- [ ] Multiple rules combine correctly

**Settings:**
- [ ] Header format customization works
- [ ] Pre/post text appears correctly
- [ ] File count limit enforced
- [ ] File size limit enforced
- [ ] Extra line toggle works
- [ ] Notification toggle works

**Edge Cases:**
- [ ] Large files skipped (size limit)
- [ ] Binary files skipped
- [ ] Empty files handled
- [ ] Files with spaces in name work
- [ ] External library files work
- [ ] Multiple labels stripped correctly (v1.0.1)

---

## 🎯 Quick Test (5 Minutes)

```bash
# 1. Start sandbox
./gradlew runIde

# 2. Create test project with files
# 3. Test CORE: Copy file from Project tree
# 4. Test GIT: Make change → Copy from Git Staging Area
# 5. Test PASTE: Delete file → Paste and Restore
# 6. Test FILTERS: Add PATH filter → verify filtering works
# 7. Test SETTINGS: Change header format → verify
```

---

## 🎉 Done!

You've tested all plugin features including v1.0.0 and v1.0.1 enhancements!

**To stop the sandbox IDE:**
- Close the IDE window or press `Ctrl+C` in terminal

**Plugin build location:**
- `build/distributions/copy-file-content-1.0.1.zip`
