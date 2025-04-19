package ir.amv.os.codemarks.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.*
import com.intellij.ui.components.JBList
import com.intellij.util.ui.FormBuilder
import ir.amv.os.codemarks.services.CodeMarkSettings
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class CodeMarkSettingsPanel(private val project: Project) : Configurable {
    private val settings = CodeMarkSettings.getInstance(project)
    private val listModel = DefaultListModel<String>()
    private val patternList = JBList(listModel)
    private var modified = false

    init {
        settings.fileTypePatterns.forEach { listModel.addElement(it) }
        patternList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        
        listModel.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) { modified = true }
            override fun intervalRemoved(e: ListDataEvent) { modified = true }
            override fun contentsChanged(e: ListDataEvent) { modified = true }
        })
    }

    override fun createComponent(): JComponent {
        val addButton = JButton("Add")
        val removeButton = JButton("Remove")
        val editButton = JButton("Edit")
        
        addButton.addActionListener {
            val pattern = JOptionPane.showInputDialog("Enter file pattern (e.g. *.java):")
            if (pattern != null && pattern.isNotBlank()) {
                listModel.addElement(pattern)
            }
        }
        
        removeButton.addActionListener {
            val selectedIndex = patternList.selectedIndex
            if (selectedIndex != -1) {
                listModel.remove(selectedIndex)
            }
        }
        
        editButton.addActionListener {
            val selectedIndex = patternList.selectedIndex
            if (selectedIndex != -1) {
                val currentPattern = listModel.getElementAt(selectedIndex)
                val newPattern = JOptionPane.showInputDialog("Edit pattern:", currentPattern)
                if (newPattern != null && newPattern.isNotBlank()) {
                    listModel.set(selectedIndex, newPattern)
                }
            }
        }
        
        val buttonPanel = JPanel()
        buttonPanel.add(addButton)
        buttonPanel.add(removeButton)
        buttonPanel.add(editButton)
        
        val listPanel = JPanel(BorderLayout())
        listPanel.add(ScrollPaneFactory.createScrollPane(patternList), BorderLayout.CENTER)
        listPanel.add(buttonPanel, BorderLayout.SOUTH)
        
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("File Patterns:", listPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        settings.fileTypePatterns.clear()
        for (i in 0 until listModel.size()) {
            settings.fileTypePatterns.add(listModel.getElementAt(i))
        }
        modified = false
    }

    override fun reset() {
        listModel.clear()
        settings.fileTypePatterns.forEach { listModel.addElement(it) }
        modified = false
    }

    override fun getDisplayName(): String = "CodeMarks"
} 