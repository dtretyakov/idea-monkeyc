package com.github.dtretyakov.monkeyc.ui.manifest

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.sdk.AppType
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.sdk.ProjectInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.CheckBoxListListener
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * The controls, and what each of them writes.
 *
 * Everything on offer comes from the SDK: the app types and permissions from `projectInfo.xml`, the
 * API levels from `compilerInfo.xml`, the devices from what the SDK Manager has downloaded. A
 * permission a watch face cannot hold is not in the list, because the compiler would reject it.
 */
internal class ManifestForm(
    private val project: Project,
    private val manifest: ManifestFile,
    private val info: ProjectInfo?,
    private val devices: List<ConnectIqDevice>,
    private val edit: ManifestEdit,
) : Disposable {

    private val appTypes: List<AppType> = info?.appTypes.orEmpty()

    private val productChoices = choices(
        installed = devices.map { it.id to "${it.displayName}  (${it.id})" },
        inManifest = manifest.devices,
    )

    private val permissionChoices = choices(
        installed = info?.permissionsFor(manifest.appType).orEmpty().map { it.id to it.name },
        inManifest = manifest.permissions,
    )

    private val languageChoices = choices(
        installed = info?.languages.orEmpty().map { it.id to "${it.name}  (${it.id})" },
        inManifest = manifest.languages,
    )

    /** Downloaded devices new enough for the minimum API level the manifest declares. */
    private val compatibleDevices: Set<String> = manifest.minSdkVersion?.let { minimum ->
        devices.filter { it.sdkVersion == null || it.sdkVersion >= minimum }.map { it.id }.toSet()
    } ?: devices.map { it.id }.toSet()

    var preferredFocusedComponent: JComponent? = null
        private set

    /**
     * What each text field would write if it lost focus now.
     *
     * A field writes when focus leaves it, and rebuilding the form takes the focus away without
     * ever telling the field — so what the user typed last would be thrown away. This is how it
     * gets written first.
     */
    private val pending = mutableListOf<() -> Unit>()

    val component: JComponent = build()

    /** Writes whatever has been typed but not yet committed. Called before the form is replaced. */
    fun flush() = pending.forEach { it() }

    override fun dispose() = Unit

    private fun build(): JComponent = panel {
        group(if (manifest.isBarrel) "Barrel" else "Application") {
            if (manifest.isBarrel) {
                row("Module:") {
                    cell(attributeField("module", manifest.module, "Change Module Name"))
                        .align(AlignX.FILL)
                        .comment("The name other projects import this barrel under.")
                }

                row("Version:") {
                    cell(attributeField("version", manifest.barrelVersion, "Change Barrel Version"))
                        .align(AlignX.FILL)
                }
            } else {
                row("Type:") {
                    comboBox(appTypes.map { it.name })
                        .applyToComponent {
                            selectedItem = appTypes.firstOrNull { it.id == manifest.appType }?.name
                            addActionListener {
                                val chosen = appTypes.firstOrNull { type -> type.name == selectedItem }
                                if (chosen != null && chosen.id != manifest.appType) {
                                    edit { it.setAttribute("type", chosen.id, "Change Application Type") }
                                }
                            }
                        }
                        .enabled(appTypes.isNotEmpty())
                }

                row("Entry class:") {
                    cell(attributeField("entry", manifest.entry, "Change Entry Class"))
                        .align(AlignX.FILL)
                        .comment("The class the device starts, which must exist in the source.")
                }

                row("Display name:") {
                    cell(attributeField("name", manifest.displayName, "Change Display Name"))
                        .align(AlignX.FILL)
                        .comment("Usually a resource, such as <code>@Strings.AppName</code>.")
                }

                row("Launcher icon:") {
                    cell(attributeField("launcherIcon", manifest.launcherIcon, "Change Launcher Icon"))
                        .align(AlignX.FILL)
                }
            }

            row("Minimum API level:") {
                val levels = info?.apiLevels.orEmpty().map { it.toString() }.reversed()
                comboBox(levels)
                    .applyToComponent {
                        selectedItem = manifest.minSdkVersion?.toString()
                        addActionListener {
                            val chosen = selectedItem as? String ?: return@addActionListener
                            if (chosen != manifest.minSdkVersion?.toString()) edit { it.setMinApiLevel(chosen) }
                        }
                    }
                    .enabled(levels.isNotEmpty())
                    .comment("Devices older than this cannot install the app.")
            }

            row(if (manifest.isBarrel) "Barrel ID:" else "Application ID:") {
                // A field rather than a label: the id is a thing people copy — into the store
                // listing, into a bug report — and a label cannot even be selected.
                cell(JBTextField(manifest.applicationId.orEmpty()).apply { isEditable = false })
                    .columns(COLUMNS_MEDIUM)
                    // Focus lands here when the tab opens: it is near the top, and typing into it
                    // does nothing. A text field that takes the first keystroke would not do.
                    .also { preferredFocusedComponent = it.component }
                button("New ID...") { regenerateId() }
                    .comment("The store knows this by its ID. A new one is a different thing to it.")
            }
        }

        group("Products") {
            val products = ChoiceList(productChoices, PRODUCTS_HEIGHT) { ids ->
                edit { model -> model.setDevices(ids) }
            }
            // A hundred and sixty devices is not a list anyone ticks one by one, and "every device
            // that can run this" is the choice most projects actually want.
            row {
                link("All") { products.select { true } }
                link("None") { products.select { false } }
                link("Compatible") { products.select { it in compatibleDevices } }
                    .enabled(compatibleDevices.isNotEmpty())
                    .comment("Downloaded devices that support the minimum API level above.")
            }
            row {
                cell(products.component)
                    .align(AlignX.FILL)
                    .comment("Start typing in the list to search. A build produces one executable per device.")
            }
        }

        if (!manifest.isBarrel) {
            // Side by side: stacked, the three lists made a form taller than any screen.
            twoColumnsRow(
                {
                    panel {
                        group("Permissions") {
                            row {
                                cell(
                                    ChoiceList(permissionChoices, SHORT_LIST_HEIGHT) { ids ->
                                        edit { model -> model.setPermissions(ids) }
                                    }.component,
                                )
                                    .align(AlignX.FILL)
                                    .comment("Only the ones this kind of app may ask for.")
                            }
                        }
                    }
                },
                {
                    panel {
                        group("Languages") {
                            row {
                                cell(
                                    ChoiceList(languageChoices, SHORT_LIST_HEIGHT) { ids ->
                                        edit { model -> model.setLanguages(ids) }
                                    }.component,
                                )
                                    .align(AlignX.FILL)
                                    .comment("The translations the app ships.")
                            }
                        }
                    }
                },
            )
        }
    }.apply { border = JBUI.Borders.empty(8) }

    /**
     * A text field that writes when it loses focus.
     *
     * Not on every keystroke: each write is a document edit, and a field that produced one per
     * character would fill the undo stack with half-typed class names.
     */
    private fun attributeField(attribute: String, value: String?, commandName: String): JTextField {
        val original = value.orEmpty()
        return JTextField(original).apply {
            pending += { if (text != original) edit { it.setAttribute(attribute, text, commandName) } }
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(event: FocusEvent) {
                        if (text != original) edit { it.setAttribute(attribute, text, commandName) }
                    }
                },
            )
        }
    }

    /** A list of ticked names, and the two ways it changes: by hand, or all at once. */
    private class ChoiceList(
        private val choices: List<Choice>,
        height: Int,
        private val onChange: (List<String>) -> Unit,
    ) {
        private val list = CheckBoxList<String>().apply {
            choices.forEach { addItem(it.id, it.label, it.selected) }
            ListSpeedSearch.installOn(this) { item -> item as? String }
        }

        val component: JComponent = JBScrollPane(list).apply { preferredSize = Dimension(LIST_WIDTH, height) }

        init {
            list.setCheckBoxListListener(CheckBoxListListener { _, _ -> onChange(selected()) })
        }

        /** Ticks every choice the predicate accepts and unticks the rest, in one edit. */
        fun select(wanted: (String) -> Boolean) {
            var changed = false
            choices.forEach { choice ->
                val target = wanted(choice.id)
                if (list.isItemSelected(choice.id) != target) {
                    list.setItemSelected(choice.id, target)
                    changed = true
                }
            }
            if (changed) {
                list.repaint()
                onChange(selected())
            }
        }

        private fun selected(): List<String> = choices.map { it.id }.filter { list.isItemSelected(it) }
    }

    /**
     * Changing the id makes this a different app to the store — a new listing, and no update path
     * for anyone who already installed it — so it is behind a question rather than a control.
     */
    private fun regenerateId() {
        val answer = Messages.showYesNoDialog(
            project,
            "The Connect IQ Store knows an app by its ID. A new one means a new listing, and no " +
                "update for anyone who has already installed this app.\n\nGenerate a new ID?",
            "New Application ID",
            "Generate",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return

        edit { it.setAttribute("id", UUID.randomUUID().toString(), "Change Application ID") }
    }

    private class Choice(val id: String, val label: String, val selected: Boolean)

    private companion object {
        const val LIST_WIDTH = 340
        const val PRODUCTS_HEIGHT = 300
        const val SHORT_LIST_HEIGHT = 220
    }

    /**
     * The list to show: what the SDK offers, plus anything the manifest already names that it does
     * not. Leaving those out would be worse than showing them — the form replaces the whole list
     * when it writes, so an entry it never displayed would be silently dropped.
     */
    private fun choices(installed: List<Pair<String, String>>, inManifest: List<String>): List<Choice> {
        val known = installed.map { (id, label) -> Choice(id, label, id in inManifest) }
        val unknown = inManifest.filterNot { id -> installed.any { it.first == id } }
            .map { Choice(it, "$it  (not installed)", true) }
        return known + unknown
    }
}
