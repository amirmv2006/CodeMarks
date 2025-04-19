package ir.amv.os.codemarks.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project

class CodeMarkSettingsConfigurable(private val project: Project) : Configurable {
    private val panel = CodeMarkSettingsPanel(project)
    
    override fun createComponent() = panel.createComponent()
    override fun isModified() = panel.isModified()
    override fun apply() = panel.apply()
    override fun reset() = panel.reset()
    override fun getDisplayName() = panel.displayName
} 