package ir.amv.os.codemarks.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.util.ui.FormBuilder
import ir.amv.os.codemarks.services.CodeMarkSettings
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CodeMarkSettingsPanel(private val project: Project) : Configurable {
    private val settings = CodeMarkSettings.getInstance(project)
    private val filePatternsField = ExpandableTextField()
    private var modified = false

    init {
        filePatternsField.text = settings.fileTypePatterns.joinToString("\n")
        filePatternsField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { modified = true }
            override fun removeUpdate(e: DocumentEvent) { modified = true }
            override fun changedUpdate(e: DocumentEvent) { modified = true }
        })
    }

    override fun createComponent(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("File Patterns (one per line):", filePatternsField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        settings.fileTypePatterns.clear()
        settings.fileTypePatterns.addAll(filePatternsField.text.lines().filter { it.isNotBlank() })
        modified = false
    }

    override fun reset() {
        filePatternsField.text = settings.fileTypePatterns.joinToString("\n")
        modified = false
    }

    override fun getDisplayName(): String = "CodeMarks"
} 