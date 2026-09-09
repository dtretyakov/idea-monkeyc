package com.github.dtretyakov.monkeyc.ui.manifest

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.sdk.AppType
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.ui.DeviceSelectionSummary
import com.github.dtretyakov.monkeyc.ui.EmptyReason
import com.github.dtretyakov.monkeyc.ui.OpenSdkManager
import com.github.dtretyakov.monkeyc.ui.ProductTable
import com.github.dtretyakov.monkeyc.sdk.ProjectInfo
import com.github.dtretyakov.monkeyc.ui.MonkeyCConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

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

    private val permissionChoices = choices(
        installed = info?.permissionsFor(manifest.appType).orEmpty().map { it.id to it.name },
        inManifest = manifest.permissions,
    )

    private val languageChoices = choices(
        installed = info?.languages.orEmpty().map { it.id to "${it.name}  (${it.id})" },
        inManifest = manifest.languages,
    )

    /**
     * Downloaded devices this app could actually be built for: new enough for the minimum API
     * level the manifest declares, and able to run the kind of app it is.
     *
     * Both halves matter and they fail differently. A device below the minimum is a choice the
     * developer can revisit; a device that runs no data fields will never run this one, and
     * ticking it produces a manifest that cannot build.
     */
    private val compatibleDevices: Set<String> = devices
        .filter { device ->
            val minimum = manifest.minSdkVersion
            (minimum == null || device.sdkVersion == null || device.sdkVersion >= minimum) &&
                device.supports(manifest.appType)
        }
        .map { it.id }
        .toSet()

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
        if (info == null) {
            // Every list on this form is filled from the SDK, so without one the page is a set of
            // empty boxes with no reason given.
            row {
                icon(AllIcons.General.Warning)
                text(
                    "No Connect IQ SDK found, so there are no devices, permissions or API levels to " +
                        "choose from. <a href='settings'>Open settings</a> or install one with Garmin's " +
                        "SDK Manager.",
                ) { ShowSettingsUtil.getInstance().showSettingsDialog(project, MonkeyCConfigurable::class.java) }
            }
        }

        group(if (manifest.isBarrel) "Barrel" else "Application") {
            if (manifest.isBarrel) {
                row("Module:") {
                    cell(attributeField("module", manifest.module, "Change Module Name"))
                        .align(AlignX.FILL)
                        .comment("The name other projects import this barrel under")
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
                        .comment("The class the device starts, which must exist in the source")
                }

                row("Display name:") {
                    cell(attributeField("name", manifest.displayName, "Change Display Name"))
                        .align(AlignX.FILL)
                        .comment("Usually a resource, such as <code>@Strings.AppName</code>")
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
                    .comment("Devices older than this cannot install the app")
            }

            row(if (manifest.isBarrel) "Barrel ID:" else "Application ID:") {
                // A field rather than a label: the id is a thing people copy — into the store
                // listing, into a bug report — and a label cannot even be selected.
                cell(JBTextField(manifest.applicationId.orEmpty()).apply { isEditable = false })
                    .columns(COLUMNS_MEDIUM)
                    // Focus lands here when the tab opens: it is near the top, and typing into it
                    // does nothing. A text field that takes the first keystroke would not do.
                    .also { preferredFocusedComponent = it.component }
                button("New ID") { regenerateId() }
                    .comment("The store knows this by its ID. A new one is a different thing to it.")
            }
        }

        row {
            cell(lists())
                .align(AlignX.FILL)
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

    /**
     * The three lists, as tabs.
     *
     * One under another they made a form taller than a screen, and side by side they were too
     * narrow for a device name. Tabs give each of them the same space and cost one click — and
     * the count in the tab says what is inside without opening it.
     */
    private fun lists(): JComponent {
        val tabs = JBTabbedPane().apply { preferredSize = Dimension(TABS_WIDTH, TABS_HEIGHT) }

        fun tab(title: String, choices: List<Choice>, help: String, list: ChoiceList) {
            val index = tabs.tabCount
            list.onCountChanged = { count -> tabs.setTitleAt(index, "$title  $count") }
            tabs.addTab("$title  ${choices.count { it.selected }}", list.panel(help))
        }

        // Products get a table rather than a list of ticked names. The four things that decide
        // whether a device belongs in the manifest — the screen a layout has to fit, the colour
        // depth artwork has to survive, whether there is a touchscreen, and the memory the code
        // has to fit — are otherwise looked up one device at a time on Garmin's website.
        // Only devices that can run this kind of app, which is four fewer than everything for a
        // watch app: an Edge 130 runs data fields and nothing else, and offering it here means the
        // Memory column is blank for a reason nobody can read. Anything the manifest already
        // declares stays, whether it fits or not — the table must be able to save what it was
        // given.
        val declared = manifest.devices.toSet()
        val runnable = devices.filter { it.supports(manifest.appType) || it.id in declared }
        val undownloaded = manifest.devices.filterNot { id -> devices.any { it.id == id } }
        val products = ProductTable(
            devices = runnable,
            selected = declared,
            appType = manifest.appType,
            undownloaded = undownloaded,
        )
        val productSummary = commentLabel(" ")
        products.onChanged = {
            val ids = products.selected()
            edit { model -> model.setDevices(ids) }
            productSummary.text =
                DeviceSelectionSummary.of(products.selectedDevices(), manifest.appType, ids.size)
                    ?: PRODUCTS_HELP
            tabs.setTitleAt(PRODUCTS_TAB, "Devices  ${ids.size}")
        }
        productSummary.text =
            DeviceSelectionSummary.of(
                products.selectedDevices(),
                manifest.appType,
                products.selected().size,
            ) ?: PRODUCTS_HELP
        // Why the list is empty, when it is. Garmin's own VS Code extension has this on file: the
        // list silently omits a device whose API level is below the manifest's minimum, and the
        // user is left looking at a shorter list with nothing to explain it. The two causes have
        // different remedies, so the empty state has to say which one it is — and this is the only
        // place products are edited now, so it has to say it here.
        val emptiness = EmptyReason.of(
            installed = devices.size,
            newEnough = devices.count { device ->
                val minimum = manifest.minSdkVersion
                minimum == null || device.sdkVersion == null || device.sdkVersion >= minimum
            },
            // The rows the table will actually have, so "empty" here means what it looks like.
            eligible = runnable.size + undownloaded.size,
            minimum = manifest.minSdkVersion,
            appType = manifest.appType,
        )

        tabs.addTab(
            "Devices  ${manifest.devices.size}",
            products.panel(
                actions = listOf(
                    "All" to { _: ConnectIqDevice -> true },
                    "None" to { _: ConnectIqDevice -> false },
                    "Compatible" to { device: ConnectIqDevice -> device.id in compatibleDevices },
                ),
                // What the selection is, and where more of it comes from, on one line: the state
                // on the left and the way to change it on the right. Under the Devices tab rather
                // than under all three, because downloading a device has nothing to do with
                // permissions or languages.
                footer = footerWith(
                    if (emptiness == EmptyReason.None) productSummary else emptyNotice(emptiness),
                    ActionLink(OpenSdkManager.label()) { OpenSdkManager.invoke(project) },
                ),
            ),
        )

        if (!manifest.isBarrel) {
            tab(
                "Permissions",
                permissionChoices,
                "Only the ones this kind of app may ask for. Ask for fewer than you can.",
                ChoiceList(permissionChoices) { ids -> edit { model -> model.setPermissions(ids) } },
            )
            tab(
                "Languages",
                languageChoices,
                "The translations the app ships.",
                ChoiceList(languageChoices, searchable = true) { ids ->
                    edit { model -> model.setLanguages(ids) }
                },
            )
        }

        return tabs
    }

    /**
     * A list of ticked names, with a search box and the bulk actions that go with one.
     *
     * The ticks are kept apart from the list widget rather than read out of it, because filtering
     * empties and refills the widget — reading the ticks off it would forget everything hidden.
     */
    private class ChoiceList(
        private val choices: List<Choice>,
        private val searchable: Boolean = false,
        private val actions: List<Pair<String, (String) -> Boolean>> = emptyList(),
        private val onChange: (List<String>) -> Unit,
    ) {
        private val ticked: MutableSet<String> = choices.filter { it.selected }.map { it.id }.toMutableSet()
        private val list = CheckBoxList<String>()
        private var visible: List<Choice> = choices

        /** Told the new count whenever the ticks change, so the tab title can say it. */
        var onCountChanged: (Int) -> Unit = {}

        init {
            fill(choices)
            list.setCheckBoxListListener { index, value ->
                visible.getOrNull(index)?.let { choice ->
                    if (value) ticked += choice.id else ticked -= choice.id
                    report()
                }
            }
        }

        fun panel(help: String): JComponent = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(8)

            val header = JPanel(BorderLayout(0, JBUI.scale(4)))
            if (actions.isNotEmpty()) {
                header.add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                        actions.forEach { (title, wanted) -> add(ActionLink(title) { select(wanted) }) }
                    },
                    BorderLayout.NORTH,
                )
            }
            if (searchable) {
                header.add(
                    SearchTextField(false).also { search ->
                        search.addDocumentListener(
                            object : DocumentAdapter() {
                                override fun textChanged(event: DocumentEvent) = filter(search.text)
                            },
                        )
                    },
                    BorderLayout.CENTER,
                )
            }
            if (header.componentCount > 0) add(header, BorderLayout.NORTH)

            add(JBScrollPane(list), BorderLayout.CENTER)
            footer = commentLabel(help)
            add(footer!!, BorderLayout.SOUTH)
        }

        /**
         * The line under the list, kept so one tab can make it say something that changes.
         *
         * Products is the tab where the ticks have consequences worth stating — how many resource
         * families they commit you to, and the smallest memory budget the code now has to fit —
         * and neither is visible anywhere else.
         */
        var footer: JComponent? = null
            private set

        /** Ticks every visible choice the predicate accepts and unticks the visible rest. */
        private fun select(wanted: (String) -> Boolean) {
            var changed = false
            visible.forEach { choice ->
                val target = wanted(choice.id)
                if ((choice.id in ticked) != target) {
                    if (target) ticked += choice.id else ticked -= choice.id
                    list.setItemSelected(choice.id, target)
                    changed = true
                }
            }
            if (changed) {
                list.repaint()
                report()
            }
        }

        private fun report() {
            val selected = selected()
            onCountChanged(selected.size)
            onChange(selected)
        }

        private fun filter(query: String) {
            val trimmed = query.trim()
            fill(if (trimmed.isEmpty()) choices else choices.filter { it.label.contains(trimmed, ignoreCase = true) })
        }

        private fun fill(items: List<Choice>) {
            visible = items
            list.clear()
            items.forEach { list.addItem(it.id, it.label, it.id in ticked) }
        }

        /** Everything ticked, hidden entries included, in the order the list declares them. */
        private fun selected(): List<String> = choices.map { it.id }.filter { it in ticked }
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
        /** Shown until a selection has something to say about itself. */
        const val PRODUCTS_HELP = "A build produces one executable per device"

        /** Products is added first, so it is tab zero. */
        const val PRODUCTS_TAB = 0

        const val TABS_WIDTH = 460
        const val TABS_HEIGHT = 340
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

/**
 * A line of explanation under a control, in the platform's comment style.
 *
 * Assembled here rather than taken from `ComponentPanelBuilder.createCommentComponent`, which
 * is what it used to be. That class is deprecated, and it acquired a Kotlin companion object
 * along the way — so a Kotlin caller compiled against the newest IDE binds to
 * `Companion.createCommentComponent` and dies with a NoSuchFieldError on any older one, which
 * is to say every time the manifest form is opened there. Three lines of JBLabel is the whole
 * of what the call did.
 */
/**
 * What to say in place of the summary when there is nothing in the table.
 *
 * The summary line answers "what does this selection cost", which is not a question an empty
 * selection has. This answers the one it does have — why is it empty — in the words the remedy
 * follows from.
 */
private fun emptyNotice(reason: EmptyReason): JBLabel = commentLabel(
    when (reason) {
        EmptyReason.None -> ""
        EmptyReason.NothingDownloaded ->
            "No devices are downloaded. A Connect IQ app is built for a device, so there is " +
                "nothing to choose from yet."

        is EmptyReason.AllBelowMinimum ->
            "None of the ${reason.installed} downloaded devices supports API level ${reason.minimum}, " +
                "which is the minimum this manifest asks for. Lower it above, or download a newer device."

        is EmptyReason.NoneRunsThisKind ->
            "None of the ${reason.installed} downloaded devices runs a ${reason.appType}. Change the " +
                "type above, or download a device that runs this one."
    },
)

/**
 * A footer row: what is true on the left, what to do about it on the right.
 *
 * Kept apart rather than folded into the summary text, because one is a statement and the other is
 * a link, and a sentence with a link buried in it reads as neither.
 */
private fun footerWith(state: JComponent, action: JComponent): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
        add(state, BorderLayout.WEST)
        add(action, BorderLayout.EAST)
    }

private fun commentLabel(text: String): JBLabel =
    JBLabel(text, UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
        border = JBUI.Borders.emptyTop(4)
        setAllowAutoWrapping(true)
        setCopyable(true)
    }
