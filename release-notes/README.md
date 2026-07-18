# 📦 Thor Release Notes Guide

Welcome! This directory contains the official release notes for Thor, structured specifically to be consumed by both developers and automated CI/CD deployment pipelines. 

Every release must have its own directory named `v[Version_Name]` (e.g., `v1.92.1`) containing exactly three variants of the changelog to cater to different distribution channels.

---

## 🛠️ Automated CI/CD Workflows Integration

These release notes files are parsed automatically during tag-pushed or master-merged CI/CD workflows:
1. **GitHub Releases Workflow**: Reads `github.md` to automatically construct the body text of the GitHub release draft.
2. **Google Play Store / Fastlane Workflow**: Extracts `playstore.txt` to populate the `whats_new` localizations for active release tracks.
3. **Telegram Channel Broadcast Bot**: Reads and formats `telegram.md` as the official broadcast message pushed to the Telegram channel.

---

## 📋 The Three Release Note Variants

For every version folder (e.g. `release-notes/v1.92.1/`), you must create these three files:

### 1️⃣ Google Play Store (`playstore.txt`)
* **Format**: Spaced plain text.
* **Size Constraint**: **Must be strictly under 500 characters** (including newlines and spaces).
* **Styling**: Short, consumer-friendly lines.
* **Line Breaks**: **MANDATORY**. Leave an empty line between each bullet point to prevent the Play Console and app listings from bunching all text into a single illegible paragraph.

### 2️⃣ Telegram Channel (`telegram.md`)
* **Format**: Markdown with high-impact, punchy emojis.
* **Size Constraint**: Short and engaging; highly mobile-optimized. Avoid heavy or long paragraphs.
* **Line Breaks**: **MANDATORY**. Leave an empty line between each bullet point to prevent Telegram's mobile client from squeezing everything into a tight, dense block.

### 3️⃣ GitHub Releases (`github.md`)
* **Format**: Full Markdown.
* **Size Constraint**: No restriction. Extremely detailed.
* **Content**: Categorize changes clearly (e.g., `🔄 Features`, `🥶 Core Changes`, `🔒 Security`, `🎨 UI/UX`). Include exact Git commit hash references (e.g. `(5f3d34d)`) to maintain open-source accountability and ease of audit.

---

## 🔄 Step-by-Step Creation Workflow (For Future Agents & Devs)

When tasked with generating release notes for a new version, follow these steps exactly:

### Step 1: Identify the Last Release Tag
List existing git tags to find the immediate previous release identifier:
```bash
git tag --sort=-v:refname
```

### Step 2: Fetch Git Logs
Fetch all committed logs from the last tag up to the current `HEAD` of the development branch to see exactly what has changed:
```bash
git log <last-release-tag>..HEAD --oneline
```

### Step 3: Categorize and Synthesize
Map the raw commits into the three variants outlined above:
* Group commits by functional area.
* Extract consumer-facing highlights for `playstore.txt`.
* Draft engaging, emoji-rich summaries for `telegram.md` (remembering the spaced lines).
* Write the detailed commit-linked breakdown for `github.md`.

### Step 4: Write and Stage Files
Create the directory and write the files:
* Path: `release-notes/v[New_Version]/`
* Files: `playstore.txt`, `telegram.md`, `github.md`

Stage the files cleanly to the index:
```bash
git add release-notes/v[New_Version]/
```
