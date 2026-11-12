package org.po00xo.chatbothelper

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

class CopyAIContext : AnAction(), DumbAware {

    private val identRegex = Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")

    private val noiseIdentifiers = setOf(
        "if", "else", "return", "null", "true", "false", "this", "super", "new",
        "class", "fun", "val", "var", "for", "while", "do", "when", "match", "case",
        "try", "catch", "finally", "throw", "throws", "import", "package",
        "private", "public", "protected", "internal", "override", "open", "final",
        "abstract", "static", "void", "int", "long", "float", "double", "boolean",
        "char", "byte", "short", "string", "object", "interface", "enum", "typealias",
        "const", "let", "def", "function", "extends", "implements", "instanceof",
        "typeof", "delete", "async", "await", "yield", "export", "default",
        "and", "or", "not", "in", "is", "as", "by", "with"
    )

    // Matches class/interface/enum/object/record declarations in Java, Kotlin, Scala, etc.
    private val classDeclarationRegex = Regex(
        """(?:^|\s)(?:abstract\s+|final\s+|open\s+|sealed\s+|data\s+|inner\s+|private\s+|public\s+|protected\s+)*(?:class|interface|enum|object|record)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        ReadAction.run<RuntimeException> {
            try {
                val document = editor.document
                val virtualFile = psiFile.virtualFile

                val highlights = DaemonCodeAnalyzerImpl.getHighlights(
                    document,
                    HighlightSeverity.ERROR,
                    project
                )

                if (highlights.isEmpty()) {
                    showNotification(project, "no errors in ${psiFile.name}")
                    return@run
                }

                val sortedHighlights = highlights.sortedBy { it.startOffset }
                val lines = document.text.lines()

                // Caches so a symbol that appears in N errors is only searched once.
                val searchCache = mutableMapOf<String, String>()
                val similarCache = mutableMapOf<String, String>()

                val output = buildString {
                    appendLine("=== Chatbot Helper: Error Analysis ===")
                    appendLine()
                    appendLine("file: ${psiFile.name}")
                    appendLine("errors found: ${sortedHighlights.size}")
                    appendLine()

                    sortedHighlights.forEachIndexed { index, highlight ->
                        val description = highlight.description ?: "unknown error"
                        val errorText = document.getText(
                            TextRange(highlight.startOffset, highlight.endOffset)
                        )
                        val unresolvedName = extractUnresolvedName(description, errorText)

                        val lineNum = document.getLineNumber(highlight.startOffset)
                        val lineStart = document.getLineStartOffset(lineNum)
                        val lineEnd = document.getLineEndOffset(lineNum)
                        val codeLine = document.getText(TextRange(lineStart, lineEnd)).trim()

                        appendLine("--- error ${index + 1}: $description ---")
                        appendLine("symbol: $unresolvedName")
                        appendLine("line ${lineNum + 1}: $codeLine")
                        appendLine()

                        // Context snippet (±3 lines around the error).
                        val contextStart = max(0, lineNum - 3)
                        val contextEnd = min(document.lineCount - 1, lineNum + 3)
                        appendLine("context:")
                        for (i in contextStart..contextEnd) {
                            val s = document.getLineStartOffset(i)
                            val e2 = document.getLineEndOffset(i)
                            val prefix = if (i == lineNum) "> " else "  "
                            appendLine("$prefix${document.getText(TextRange(s, e2))}")
                        }
                        appendLine()

                        // Parent class hierarchy of the enclosing class.
                        // This is the "jog the AI's memory" part: if the code lives
                        // inside `class Foo extends Bar implements Baz`, the AI can
                        // reason about which base type might actually define the
                        // unresolved symbol.
                        appendLine("parent classes of enclosing context:")
                        val classDecl = findEnclosingClassDeclaration(lines, lineNum)
                        if (classDecl != null) {
                            appendLine(classDecl)
                        } else {
                            appendLine("  (no enclosing class declaration found)")
                        }
                        appendLine()

                        // Project-wide search for the unresolved symbol (cached).
                        if (searchCache.containsKey(unresolvedName)) {
                            appendLine("available references for $unresolvedName: (already shown above)")
                        } else {
                            val searchResult = searchProject(project, unresolvedName)
                            searchCache[unresolvedName] = searchResult
                            appendLine("available references for $unresolvedName in project:")
                            appendLine(searchResult)
                        }
                        appendLine()

                        // Similar references in nearby files (cached).
                        if (similarCache.containsKey(unresolvedName)) {
                            appendLine("similar references for $unresolvedName: (already shown above)")
                        } else {
                            val simResult = findSimilarReferences(project, unresolvedName, virtualFile)
                            similarCache[unresolvedName] = simResult
                            appendLine("similar references near current file for $unresolvedName:")
                            appendLine("candidate is a possible typo or wrong name")
                            appendLine(simResult)
                        }
                        appendLine()
                    }

                    // Errors in other open files (unchanged behavior).
                    appendLine("=== errors in other open files ===")
                    appendLine(getAllOpenFilesErrors(project))
                }

                copyToClipboard(output)
                showNotification(
                    project,
                    "copied analysis of ${sortedHighlights.size} error(s) from ${psiFile.name}"
                )
            } catch (ex: Exception) {
                showNotification(project, "failed: ${ex.message}")
                ex.printStackTrace()
            }
        }
    }

    // ------------------------------------------------------------------
    // Parent class hierarchy extraction (text-based, language-agnostic)
    // ------------------------------------------------------------------

    /**
     * Scans upwards from [errorLineIndex] to find the nearest class/interface/enum/object
     * declaration line. Returns the declaration block (up to the opening brace) or null.
     */
    private fun findEnclosingClassDeclaration(lines: List<String>, errorLineIndex: Int): String? {
        for (i in errorLineIndex downTo 0) {
            val line = lines[i]
            if (classDeclarationRegex.containsMatchIn(line)) {
                // Collect lines until we hit the opening brace or a reasonable limit.
                val sb = StringBuilder()
                var j = i
                while (j < lines.size && j <= i + 10) {
                    sb.appendLine(lines[j])
                    if (lines[j].contains("{")) break
                    j++
                }
                return sb.toString().trimEnd()
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Everything below is unchanged from the stable version
    // ------------------------------------------------------------------

    private fun extractUnresolvedName(description: String, errorText: String): String {
        val patterns = listOf(
            Regex("['\"]?([a-zA-Z_][\\w]*)['\"]?\\s*(?:in|$)", RegexOption.IGNORE_CASE),
            Regex(":\\s*['\"]?([a-zA-Z_][\\w]*)['\"]?"),
            Regex("symbol\\s+['\"]?([a-zA-Z_][\\w]*)['\"]?"),
            Regex("reference:\\s*['\"]?([a-zA-Z_][\\w]*)['\"]?"),
            Regex("method\\s+['\"]?([a-zA-Z_][\\w]*)['\"]?"),
            Regex("class\\s+['\"]?([a-zA-Z_][\\w]*)['\"]?")
        )

        for (pattern in patterns) {
            pattern.find(description)?.let {
                val name = it.groupValues[1]
                if (name.isNotBlank()) return name
            }
        }

        val identifiers = errorText.split(Regex("[\\s.(),;]+"))
            .filter { it.matches(Regex("[a-zA-Z_][\\w]*")) && !it.equals("import", ignoreCase = true) }

        return identifiers.lastOrNull() ?: errorText
    }

    private fun searchProject(project: Project, name: String): String {
        if (name.length < 2 || name == "Unknown") return "symbol name too short to search reliably"

        val result = StringBuilder()
        val projectRoot = project.basePath ?: return "project path not available"

        val sourceFiles = mutableListOf<VirtualFile>()
        collectSourceFiles(project, sourceFiles)

        if (sourceFiles.isEmpty()) {
            return "no source files found in project"
        }

        val classPattern = Regex("""(?:public\s+|private\s+|protected\s+)?(?:abstract\s+|final\s+|open\s+)?(?:class|interface|enum|object|data\s+class)\s+($name)(?:\s*[,<(:]|\s*$)""")
        val functionPattern = Regex("""(?:public\s+|private\s+|protected\s+)?(?:static\s+|open\s+|override\s+|suspend\s+|inline\s+)?(?:fun|def|function)\s+($name)\s*[<(]""")
        val propertyPattern = Regex("""(?:public\s+|private\s+|protected\s+)?(?:val|var|let|const)\s+($name)(?:\s*[:=]|\s*$)""")
        val importExact = Regex("""import\s+(?:[\w.]+\.)?$name(?:\s|$)""")
        val importContaining = Regex("""import\s+[\w.]*${Regex.escape(name)}[\w.]*""")

        val usagePattern = Regex("""\b$name\b""")

        result.appendLine("searching ${sourceFiles.size} source files for $name")
        result.appendLine()

        var classMatches = 0
        var functionMatches = 0
        var propertyMatches = 0
        var importMatches = 0

        for (file in sourceFiles.take(500)) {
            try {
                val text = readFileContent(file) ?: continue
                val relativePath = getRelativePath(project, file)

                val classes = classPattern.findAll(text).toList()
                val functions = functionPattern.findAll(text).toList()
                val properties = propertyPattern.findAll(text).toList()
                val imports = importExact.findAll(text).toList()
                val containingImports = if (imports.isEmpty()) importContaining.findAll(text).toList() else emptyList()

                if (classes.isNotEmpty()) {
                    classMatches += classes.size
                    result.appendLine(relativePath)
                    classes.forEach { match ->
                        val lineNum = getLineNumber(text, match.range.first)
                        result.appendLine("  line $lineNum: ${match.value}")
                    }
                }
                if (functions.isNotEmpty()) {
                    functionMatches += functions.size
                    if (classes.isEmpty()) result.appendLine(relativePath)
                    functions.forEach { match ->
                        val lineNum = getLineNumber(text, match.range.first)
                        result.appendLine("  method line $lineNum: ${match.value}")
                    }
                }
                if (properties.isNotEmpty()) {
                    propertyMatches += properties.size
                    if (classes.isEmpty() && functions.isEmpty()) result.appendLine(relativePath)
                    properties.forEach { match ->
                        val lineNum = getLineNumber(text, match.range.first)
                        result.appendLine("  field line $lineNum: ${match.value}")
                    }
                }
                if (imports.isNotEmpty() || containingImports.isNotEmpty()) {
                    importMatches += 1
                    if (classes.isEmpty() && functions.isEmpty() && properties.isEmpty()) {
                        result.appendLine("$relativePath imports mentioning $name")
                    }
                }
            } catch (_: Exception) { }
        }

        result.appendLine()
        result.appendLine("summary")
        result.appendLine("classes interfaces enums: $classMatches")
        result.appendLine("functions methods: $functionMatches")
        result.appendLine("properties fields: $propertyMatches")
        result.appendLine("files with matching imports: $importMatches")

        if (classMatches == 0 && functionMatches == 0 && propertyMatches == 0) {
            result.appendLine()
            result.appendLine("no direct matches found, trying broader search for $name in project")
            result.appendLine()
            var usageCount = 0
            for (file in sourceFiles.take(200)) {
                try {
                    val text = readFileContent(file) ?: continue
                    val usages = usagePattern.findAll(text).toList()
                    if (usages.isNotEmpty() && !text.contains("${name}Context") && !text.contains("${name}Service")) {
                        usageCount += usages.size
                    }
                } catch (_: Exception) { }
            }
            result.appendLine("occurrences of $name in project: ~$usageCount times")
        }

        return result.toString()
    }

    private fun collectSourceFiles(project: Project, files: MutableList<VirtualFile>) {
        val projectDir = project.baseDir ?: return
        val sourceExtensions = setOf("java", "kt", "kts", "groovy", "scala", "py", "js", "ts", "xml", "gradle")

        VfsUtilCore.iterateChildrenRecursively(projectDir, null) { file ->
            if (!file.isDirectory) {
                val ext = file.extension ?: return@iterateChildrenRecursively true
                if (ext in sourceExtensions && !file.path.contains("/.git/") && !file.path.contains("/build/") && !file.path.contains("/.gradle/") && !file.path.contains("/node_modules/") && !file.path.contains("/target/") && !file.path.contains("/out/")) {
                    files.add(file)
                }
            }
            true
        }
    }

    private fun readFileContent(file: VirtualFile): String? {
        return try {
            val inputStream = file.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.use { it.readText() }
        } catch (_: Exception) { null }
    }

    private fun getRelativePath(project: Project, file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val filePath = file.path
        return if (filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).trimStart('/')
        } else {
            filePath
        }
    }

    private fun getLineNumber(text: String, offset: Int): Int {
        return text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1
    }

    private fun getAllOpenFilesErrors(project: Project): String {
        val sb = StringBuilder()
        val fileEditorManager = FileEditorManager.getInstance(project)
        val fileDocumentManager = FileDocumentManager.getInstance()

        for (file in fileEditorManager.openFiles) {
            if (!file.isValid || file.isDirectory) continue
            val document = fileDocumentManager.getDocument(file) ?: continue
            val highlights = DaemonCodeAnalyzerImpl.getHighlights(
                document,
                HighlightSeverity.ERROR,
                project
            )
            if (highlights.isNotEmpty()) {
                sb.appendLine(file.presentableName)
                for (h in highlights.take(20)) {
                    val line = document.getLineNumber(h.startOffset) + 1
                    val desc = h.description ?: "error"
                    sb.appendLine("  line $line: $desc")
                }
                sb.appendLine()
            }
        }
        return sb.toString().ifEmpty { "no other errors in open files" }
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun showNotification(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Chatbot Helper")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun findSimilarReferences(project: Project, name: String, currentFile: VirtualFile?): String {
        if (name.length < 2) return "symbol name too short for similarity search"

        val candidateFiles = mutableSetOf<VirtualFile>()
        currentFile?.let { candidateFiles.add(it) }

        val directory = currentFile?.parent
        val siblingFiles = directory?.children?.filter { isSourceFile(it) } ?: emptyList()
        candidateFiles.addAll(siblingFiles)

        val parentDirFiles = directory?.parent?.children?.filter { isSourceFile(it) } ?: emptyList()
        candidateFiles.addAll(parentDirFiles)

        if (candidateFiles.isEmpty()) return "no source files found near current file"

        data class Hit(val identifier: String, val file: String, val line: Int, val score: Double)

        val hits = mutableListOf<Hit>()
        val seen = mutableSetOf<String>()

        for (file in candidateFiles) {
            try {
                val text = readFileContent(file) ?: continue
                val relPath = getRelativePath(project, file)
                val lines = text.lines()

                for ((idx, line) in lines.withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue

                    for (match in identRegex.findAll(line)) {
                        val ident = match.value
                        if (ident.equals(name, ignoreCase = false)) continue
                        if (ident.length < 3) continue
                        if (ident.lowercase() in noiseIdentifiers) continue

                        val score = similarity(name, ident)
                        if (score < 0.55) continue

                        val key = "$relPath:$idx:$ident"
                        if (seen.add(key)) {
                            hits.add(Hit(ident, relPath, idx + 1, score))
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        val result = StringBuilder()

        if (hits.isEmpty()) {
            result.appendLine("no similarly named references found near current file")
            return result.toString()
        }

        val grouped = hits.groupBy { it.identifier }
            .mapValues { (_, hs) -> hs.sortedByDescending { it.score } }
            .toList()
            .sortedByDescending { (_, hs) -> hs.first().score }
            .take(15)

        result.appendLine("files searched: ${candidateFiles.size} current siblings parent dir")
        result.appendLine("higher score means more likely what was meant")
        result.appendLine()

        for ((ident, hs) in grouped) {
            val best = hs.first()
            val simPercent = (best.score * 100).toInt()
            result.appendLine("  $ident $simPercent percent")
            val uniqueLocs = hs.map { it.file to it.line }.distinct().take(3)
            for ((f, l) in uniqueLocs) {
                result.appendLine("    at $f:$l")
            }
        }

        return result.toString()
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val ext = file.extension ?: return false
        return ext in setOf("java", "kt", "kts", "scala", "groovy", "py", "js", "ts")
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val charSim = charSimilarity(a, b)
        val tokSim = tokenSimilarity(a, b)

        return 0.4 * charSim + 0.6 * tokSim
    }

    private fun charSimilarity(a: String, b: String): Double {
        val la = a.lowercase()
        val lb = b.lowercase()

        if (la == lb) return 1.0

        if (la.startsWith(lb) || lb.startsWith(la)) {
            val minLen = min(la.length, lb.length).toDouble()
            val maxLen = max(la.length, lb.length).toDouble()
            return 0.85 + 0.15 * (minLen / maxLen)
        }

        if (la.endsWith(lb) || lb.endsWith(la)) {
            val minLen = min(la.length, lb.length).toDouble()
            val maxLen = max(la.length, lb.length).toDouble()
            return 0.75 + 0.15 * (minLen / maxLen)
        }

        val dist = levenshtein(la, lb)
        val maxLen = max(la.length, lb.length).toDouble()
        return (1.0 - dist / maxLen).coerceAtLeast(0.0)
    }

    private fun tokenSimilarity(a: String, b: String): Double {
        val ta = tokenize(a)
        val tb = tokenize(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0

        val sa = ta.toSet()
        val sb = tb.toSet()
        val common = sa.intersect(sb).size.toDouble()
        val union = sa.union(sb).size.toDouble()
        var jaccard = if (union == 0.0) 0.0 else common / union

        if (ta.firstOrNull() == tb.firstOrNull()) {
            jaccard = min(1.0, jaccard + 0.2)
        }

        return jaccard
    }

    private fun tokenize(ident: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (i in ident.indices) {
            val c = ident[i]
            if (c == '_' || c == '-') {
                if (current.isNotEmpty()) {
                    parts.add(current.toString())
                    current.clear()
                }
                continue
            }
            if (c.isUpperCase() && current.isNotEmpty() && current.last().isLowerCase()) {
                parts.add(current.toString())
                current.clear()
            }
            current.append(c)
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts.map { it.lowercase() }
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}