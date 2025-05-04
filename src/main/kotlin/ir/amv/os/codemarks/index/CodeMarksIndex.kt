package ir.amv.os.codemarks.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.*
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.Document
import ir.amv.os.codemarks.services.CodeMarkSettings
import java.io.DataInput
import java.io.DataOutput
import java.util.regex.Pattern

/**
 * FileBasedIndex implementation for CodeMarks comments.
 * This index stores information about CodeMarks comments in files:
 * - File path
 * - Line number
 * - Comment description
 * - Optional group/suffix from the comment
 */
class CodeMarksIndex : FileBasedIndexExtension<String, List<CodeMarkInfo>>() {
    companion object {
        // The name of the index
        val NAME: ID<String, List<CodeMarkInfo>> = ID.create("ir.amv.os.codemarks.index.CodeMarksIndex")

        // The pattern to match CodeMarks comments
        private val BOOKMARK_PATTERN = Pattern.compile("CodeMarks(?:\\[(\\w+)\\])?:\\s*(.*)", Pattern.CASE_INSENSITIVE)

        // Helper method to check if a file name matches a glob pattern
        private fun matchesGlob(fileName: String, glob: String): Boolean {
            if (glob == "*") return true
            val fs = java.nio.file.FileSystems.getDefault()
            val matcher = fs.getPathMatcher("glob:$glob")
            return matcher.matches(fs.getPath(fileName))
        }

        // Helper method to check if a file should be indexed based on glob patterns
        fun shouldIndexFile(file: VirtualFile, project: Project): Boolean {
            if (file.isDirectory) return false
            val fileName = file.name
            val settings = CodeMarkSettings.getInstance(project)
            return settings.fileTypePatterns.any { pattern ->
                try {
                    matchesGlob(fileName, pattern)
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    override fun getName(): ID<String, List<CodeMarkInfo>> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, List<CodeMarkInfo>, FileContent> = 
        DataIndexer { fileContent ->
            val result = HashMap<String, List<CodeMarkInfo>>()
            val file = fileContent.file
            val project = fileContent.project

            if (!shouldIndexFile(file, project)) {
                return@DataIndexer result
            }

            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@DataIndexer result
            val text = document.text
            val codeMarks = mutableListOf<CodeMarkInfo>()

            // Use regex to find all CodeMarks comments in the file
            val matcher = BOOKMARK_PATTERN.matcher(text)
            while (matcher.find()) {
                val startOffset = matcher.start()
                val lineNumber = document.getLineNumber(startOffset)
                val suffix = matcher.group(1)
                val description = matcher.group(2).trim()

                codeMarks.add(CodeMarkInfo(file.path, lineNumber, description, suffix))
            }

            if (codeMarks.isNotEmpty()) {
                result[file.path] = codeMarks
            }

            result
        }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<CodeMarkInfo>> = 
        object : DataExternalizer<List<CodeMarkInfo>> {
            override fun save(out: DataOutput, value: List<CodeMarkInfo>) {
                out.writeInt(value.size)
                for (info in value) {
                    out.writeUTF(info.filePath)
                    out.writeInt(info.lineNumber)
                    out.writeUTF(info.description)
                    out.writeBoolean(info.suffix != null)
                    if (info.suffix != null) {
                        out.writeUTF(info.suffix)
                    }
                }
            }

            override fun read(input: DataInput): List<CodeMarkInfo> {
                val size = input.readInt()
                val result = ArrayList<CodeMarkInfo>(size)
                for (i in 0 until size) {
                    val filePath = input.readUTF()
                    val lineNumber = input.readInt()
                    val description = input.readUTF()
                    val hasSuffix = input.readBoolean()
                    val suffix = if (hasSuffix) input.readUTF() else null
                    result.add(CodeMarkInfo(filePath, lineNumber, description, suffix))
                }
                return result
            }
        }

    override fun getInputFilter(): FileBasedIndex.InputFilter = 
        FileBasedIndex.InputFilter { file ->
            // We'll do more detailed filtering in the indexer
            !file.isDirectory
        }
}

/**
 * Data class to store information about a CodeMark comment.
 */
data class CodeMarkInfo(
    val filePath: String,
    val lineNumber: Int,
    val description: String,
    val suffix: String?
)
