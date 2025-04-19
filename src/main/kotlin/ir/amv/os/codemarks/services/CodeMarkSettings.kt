package ir.amv.os.codemarks.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.components.service

@State(
    name = "CodeMarkSettings",
    storages = [Storage("codemarks.xml")]
)
class CodeMarkSettings : PersistentStateComponent<CodeMarkSettings> {
    var fileTypePatterns: MutableSet<String> = mutableSetOf("*")
    var lastScanState: MutableMap<String, Long> = ConcurrentHashMap()
    
    override fun getState(): CodeMarkSettings = this
    
    override fun loadState(state: CodeMarkSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    companion object {
        fun getInstance(project: Project): CodeMarkSettings = project.service<CodeMarkSettings>()
    }
} 