package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun DesktopOptionsScreen(
    deckNames: List<String>,
    optionsByDeck: Map<String, DeckOptions>,
    syncing: Boolean,
    schedulerProfile: SchedulerProfileState = SchedulerProfileState(),
    studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy.systemDefault(),
    schedulerOptimizer: SchedulerOptimizerState = SchedulerOptimizerState(),
    deckPresets: DeckPresetState = DeckPresetState(),
    signedIn: Boolean = false,
    initialDeckName: String? = null,
    onDecks: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onSync: () -> Unit,
    onCommands: () -> Unit = {},
    onPlugins: () -> Unit = {},
    onSave: suspend (String, DeckOptions) -> String?,
    onApplyAccountProfile: suspend (DeckOptions, Boolean) -> String? = { _, _ -> null },
    onApplyCloudProfile: suspend () -> String? = { null },
    onSaveStudyDayPolicy: suspend (String, Int) -> String? = { _, _ -> null },
    onStartOptimization: () -> Unit = {},
    onCancelOptimization: () -> Unit = {},
    onApplyOptimizerCandidate: suspend (Boolean) -> String? = { null },
    onDiscardOptimizerCandidate: suspend () -> String? = { null },
    onAssignPreset: suspend (String, String?) -> String? = { _, _ -> null },
    onCreatePreset: suspend (String, String, DeckOptions) -> String? = { _, _, _ -> null },
    onClonePreset: suspend (String, String, String) -> String? = { _, _, _ -> null },
    onRenamePreset: suspend (String, String) -> String? = { _, _ -> null },
    onDeletePreset: suspend (String) -> String? = { null },
) {
    var selectedDeck by remember(deckNames, initialDeckName) {
        mutableStateOf(
            initialDeckName?.takeIf { requested -> deckNames.any { it.equals(requested, ignoreCase = true) } }
                ?: deckNames.firstOrNull().orEmpty(),
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(Modifier.safeContentPadding()) {
            DesktopTopToolbar(
                onDecks = onDecks,
                onAdd = onAdd,
                onBrowse = onBrowse,
                onOptions = {},
                onSync = onSync,
                syncing = syncing,
                activeItem = "Options",
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Button(onClick = onCommands, modifier = Modifier.testTag("desktop-command-palette")) {
                    Text("Commands")
                }
                Button(onClick = onPlugins, modifier = Modifier.testTag("desktop-plugin-manager")) {
                    Text("Plugins")
                }
            }
            if (selectedDeck.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Create a deck before configuring options", color = KelmaDesktopColors.TextSecondary)
                }
            } else {
                key(selectedDeck, optionsByDeck[selectedDeck], studyDayPolicy) {
                    DeckOptionsEditor(
                        deckNames = deckNames,
                        selectedDeck = selectedDeck,
                        initial = optionsByDeck[selectedDeck] ?: DeckOptions(),
                        syncing = syncing,
                        schedulerProfile = schedulerProfile,
                        studyDayPolicy = studyDayPolicy,
                        schedulerOptimizer = schedulerOptimizer,
                        deckPresets = deckPresets,
                        signedIn = signedIn,
                        onSelectDeck = { selectedDeck = it },
                        onSave = onSave,
                        onApplyAccountProfile = onApplyAccountProfile,
                        onApplyCloudProfile = onApplyCloudProfile,
                        onSaveStudyDayPolicy = onSaveStudyDayPolicy,
                        onStartOptimization = onStartOptimization,
                        onCancelOptimization = onCancelOptimization,
                        onApplyOptimizerCandidate = onApplyOptimizerCandidate,
                        onDiscardOptimizerCandidate = onDiscardOptimizerCandidate,
                        onAssignPreset = onAssignPreset,
                        onCreatePreset = onCreatePreset,
                        onClonePreset = onClonePreset,
                        onRenamePreset = onRenamePreset,
                        onDeletePreset = onDeletePreset,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckOptionsEditor(
    deckNames: List<String>,
    selectedDeck: String,
    initial: DeckOptions,
    syncing: Boolean,
    schedulerProfile: SchedulerProfileState,
    studyDayPolicy: AccountStudyDayPolicy,
    schedulerOptimizer: SchedulerOptimizerState,
    deckPresets: DeckPresetState,
    signedIn: Boolean,
    onSelectDeck: (String) -> Unit,
    onSave: suspend (String, DeckOptions) -> String?,
    onApplyAccountProfile: suspend (DeckOptions, Boolean) -> String?,
    onApplyCloudProfile: suspend () -> String?,
    onSaveStudyDayPolicy: suspend (String, Int) -> String?,
    onStartOptimization: () -> Unit,
    onCancelOptimization: () -> Unit,
    onApplyOptimizerCandidate: suspend (Boolean) -> String?,
    onDiscardOptimizerCandidate: suspend () -> String?,
    onAssignPreset: suspend (String, String?) -> String?,
    onCreatePreset: suspend (String, String, DeckOptions) -> String?,
    onClonePreset: suspend (String, String, String) -> String?,
    onRenamePreset: suspend (String, String) -> String?,
    onDeletePreset: suspend (String) -> String?,
) {
    var newLimit by remember { mutableStateOf(initial.newCardsPerDay.toString()) }
    var reviewLimit by remember { mutableStateOf(initial.maximumReviewsPerDay.toString()) }
    var learningSteps by remember { mutableStateOf(formatMinuteSteps(initial.learningStepsMinutes)) }
    var relearningSteps by remember { mutableStateOf(formatMinuteSteps(initial.relearningStepsMinutes)) }
    var autoplay by remember { mutableStateOf(initial.autoplayAudio) }
    var answerSeconds by remember { mutableStateOf(initial.maximumAnswerSeconds.toString()) }
    var confirmBeforeUndo by remember { mutableStateOf(initial.confirmBeforeUndo) }
    var retention by remember { mutableStateOf((initial.desiredRetention * 100).toString()) }
    var parameters by remember { mutableStateOf(initial.fsrsParameters.joinToString(", ")) }
    val schedulerAlgorithm = initial.effectiveSchedulerAlgorithm
    var maximumInterval by remember { mutableStateOf(initial.maximumIntervalDays.toString()) }
    var newGatherOrder by remember { mutableStateOf(initial.newCardGatherOrder) }
    var newSortOrder by remember { mutableStateOf(initial.newCardSortOrder) }
    var newMixOrder by remember { mutableStateOf(initial.newReviewMixOrder) }
    var interdayMixOrder by remember { mutableStateOf(initial.interdayLearningMixOrder) }
    var reviewSortOrder by remember { mutableStateOf(initial.reviewSortOrder) }
    var buryNewSiblings by remember { mutableStateOf(initial.buryNewSiblings) }
    var buryReviewSiblings by remember { mutableStateOf(initial.buryReviewSiblings) }
    var buryInterdaySiblings by remember { mutableStateOf(initial.buryInterdayLearningSiblings) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentOptions(): DeckOptions = DeckOptions(
        newCardsPerDay = newLimit.toInt(),
        maximumReviewsPerDay = reviewLimit.toInt(),
        learningStepsMinutes = parseMinuteSteps(learningSteps),
        relearningStepsMinutes = parseMinuteSteps(relearningSteps),
        autoplayAudio = autoplay,
        maximumAnswerSeconds = answerSeconds.toInt(),
        confirmBeforeUndo = confirmBeforeUndo,
        desiredRetention = retention.toDouble() / 100.0,
        fsrsParameters = parameters.split(Regex("[,\\s]+"))
            .filter(String::isNotBlank).map(String::toDouble),
        schedulerAlgorithm = schedulerAlgorithm,
        maximumIntervalDays = maximumInterval.toInt(),
        newCardGatherOrder = newGatherOrder,
        newCardSortOrder = newSortOrder,
        newReviewMixOrder = newMixOrder,
        interdayLearningMixOrder = interdayMixOrder,
        reviewSortOrder = reviewSortOrder,
        buryNewSiblings = buryNewSiblings,
        buryReviewSiblings = buryReviewSiblings,
        buryInterdayLearningSiblings = buryInterdaySiblings,
    ).validated()

    fun save() {
        if (saving || syncing) return
        val options = try {
            currentOptions()
        } catch (exception: Exception) {
            message = exception.message ?: "Check the option values"
            messageIsError = true
            return
        }
        saving = true
        message = null
        scope.launch {
            val error = onSave(selectedDeck, options)
            saving = false
            message = error ?: "Options saved on this device"
            messageIsError = error != null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.width(940.dp).padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("OPTIONS", color = KelmaDesktopColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(selectedDeck, color = KelmaDesktopColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            DeckSelector(deckNames, selectedDeck, onSelectDeck)
            Button(
                onClick = ::save,
                enabled = !saving && !syncing,
                modifier = Modifier.padding(start = 12.dp).testTag("options-save"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KelmaDesktopColors.Gold,
                    contentColor = KelmaDesktopColors.Background,
                ),
            ) { Text(if (saving) "Saving…" else "Save", fontWeight = FontWeight.Bold) }
        }
        message?.let {
            Text(
                it,
                modifier = Modifier.width(940.dp).padding(bottom = 10.dp),
                color = if (messageIsError) KelmaColors.Bad else KelmaDesktopColors.Accent,
                fontSize = 13.sp,
            )
        }
        Row(
            modifier = Modifier.width(940.dp).padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OptionsSection("Daily limits") {
                    NumberOption("New cards/day", newLimit, { newLimit = it }, "options-new-limit")
                    NumberOption("Maximum reviews/day", reviewLimit, { reviewLimit = it })
                    DisabledOption("New cards ignore review limit")
                    DisabledOption("Limits start from top")
                }
                OptionsSection("New cards") {
                    TextOption("Learning steps", learningSteps, { learningSteps = it }, "1m 10m")
                    DisabledOption("Insertion order")
                }
                OptionsSection("Lapses") {
                    TextOption("Relearning steps", relearningSteps, { relearningSteps = it }, "10m")
                    DisabledOption("Leech threshold")
                    DisabledOption("Leech action")
                }
                OptionsSection("Display order") {
                    EnumOption(
                        "New card gather order", newGatherOrder, NewCardGatherOrder.entries,
                        { it.label }, { newGatherOrder = it }, "options-new-gather-order",
                    )
                    EnumOption(
                        "New card sort order", newSortOrder, NewCardSortOrder.entries,
                        { it.label }, { newSortOrder = it }, "options-new-sort-order",
                    )
                    EnumOption(
                        "New/review order", newMixOrder, QueueMixOrder.entries,
                        { it.label }, { newMixOrder = it }, "options-new-mix-order",
                    )
                    EnumOption(
                        "Interday learning/review order", interdayMixOrder, QueueMixOrder.entries,
                        { it.label }, { interdayMixOrder = it }, "options-interday-mix-order",
                    )
                    EnumOption(
                        "Review sort order", reviewSortOrder, ReviewSortOrder.entries,
                        { it.label }, { reviewSortOrder = it }, "options-review-sort-order",
                    )
                }
                OptionsSection(schedulerAlgorithm.label) {
                    FixedSwitch("FSRS", true)
                    NumberOption("Desired retention (%)", retention, { retention = it }, "options-retention")
                    TextOption(
                        "FSRS parameters", parameters, { parameters = it },
                        "${schedulerAlgorithm.parameterCount} comma-separated values", minLines = 3,
                    )
                    SchedulerOptimizerControls(
                        state = schedulerOptimizer,
                        signedIn = signedIn,
                        enabled = schedulerAlgorithm == SchedulerAlgorithm.Fsrs6 && !syncing,
                        compact = true,
                        onStart = onStartOptimization,
                        onCancel = onCancelOptimization,
                        onApply = onApplyOptimizerCandidate,
                        onDiscard = onDiscardOptimizerCandidate,
                    )
                    DesktopAccountSchedulerProfileControls(
                        state = schedulerProfile,
                        signedIn = signedIn,
                        enabled = schedulerAlgorithm == SchedulerAlgorithm.Fsrs6 && !syncing,
                        onApplyCurrent = { publish ->
                            runCatching { onApplyAccountProfile(currentOptions(), publish) }
                                .getOrElse { it.message ?: "Check the scheduler profile values" }
                        },
                        onApplyCloud = onApplyCloudProfile,
                    )
                    DisabledOption("Evaluate parameters")
                    DisabledOption("FSRS simulator")
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OptionsSection("Account study day") {
                    DesktopStudyDayPolicyControls(
                        policy = studyDayPolicy,
                        signedIn = signedIn,
                        syncing = syncing,
                        onSave = onSaveStudyDayPolicy,
                    )
                }
                OptionsSection("Burying") {
                    ToggleOption("Bury new siblings", buryNewSiblings, "options-bury-new") {
                        buryNewSiblings = it
                    }
                    ToggleOption("Bury review siblings", buryReviewSiblings, "options-bury-review") {
                        buryReviewSiblings = it
                    }
                    ToggleOption("Bury interday learning siblings", buryInterdaySiblings, "options-bury-interday") {
                        buryInterdaySiblings = it
                    }
                }
                OptionsSection("Audio") {
                    ToggleOption("Automatically play audio", autoplay) { autoplay = it }
                    DisabledOption("Skip question audio when replaying answer")
                }
                OptionsSection("Timer") {
                    NumberOption("Maximum answer seconds", answerSeconds, { answerSeconds = it })
                    ToggleOption("Confirm before undo", confirmBeforeUndo, "options-confirm-undo") {
                        confirmBeforeUndo = it
                    }
                    DisabledOption("Show answer timer")
                    DisabledOption("Stop timer on answer")
                }
                OptionsSection("Auto advance") {
                    DisabledOption("Seconds to show question")
                    DisabledOption("Seconds to show answer")
                    DisabledOption("Wait for audio")
                    DisabledOption("Question action")
                    DisabledOption("Answer action")
                }
                OptionsSection("Easy days") {
                    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                        .forEach { day -> DisabledOption(day) }
                }
                OptionsSection("Advanced") {
                    NumberOption("Maximum interval (days)", maximumInterval, { maximumInterval = it })
                    DisabledOption("Historical retention")
                    DisabledOption("Ignore reviews before")
                    DisabledOption("Custom scheduling")
                }
                OptionsSection("Presets") {
                    DeckPresetControls(
                        state = deckPresets,
                        deckName = selectedDeck,
                        desktop = true,
                        currentOptions = ::currentOptions,
                        onAssign = onAssignPreset,
                        onCreate = onCreatePreset,
                        onClone = onClonePreset,
                        onRename = onRenamePreset,
                        onDelete = onDeletePreset,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> EnumOption(
    label: String,
    selected: T,
    choices: List<T>,
    choiceLabel: (T) -> String,
    onSelect: (T) -> Unit,
    tag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = KelmaDesktopColors.TextSecondary, fontSize = 12.sp)
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.testTag(tag),
                color = KelmaDesktopColors.Background,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KelmaDesktopColors.Border),
            ) {
                Row(
                    Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(choiceLabel(selected), Modifier.weight(1f), color = KelmaDesktopColors.TextPrimary, fontSize = 13.sp)
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = KelmaDesktopColors.TextSecondary)
                }
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = KelmaDesktopColors.Surface) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choiceLabel(choice), color = KelmaDesktopColors.TextPrimary) },
                        onClick = { expanded = false; onSelect(choice) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckSelector(deckNames: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier.width(260.dp).clickable { expanded = true }.testTag("options-deck-selector"),
            color = KelmaDesktopColors.Surface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, KelmaDesktopColors.Border),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected, modifier = Modifier.weight(1f), color = KelmaDesktopColors.TextPrimary, fontSize = 13.sp)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose deck", tint = KelmaDesktopColors.TextSecondary)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(260.dp),
            containerColor = KelmaDesktopColors.Surface,
        ) {
            deckNames.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, color = KelmaDesktopColors.TextPrimary) },
                    onClick = { onSelect(name); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun OptionsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KelmaDesktopColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title.uppercase(), color = KelmaDesktopColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun NumberOption(label: String, value: String, onChange: (String) -> Unit, tag: String? = null) =
    TextOption(label, value, { onChange(it.filter { char -> char.isDigit() || char == '.' }) }, "", tag = tag)

@Composable
private fun TextOption(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1,
    tag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag)),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = minLines,
        singleLine = minLines == 1,
    )
}

@Composable
private fun ToggleOption(
    label: String,
    checked: Boolean,
    tag: String? = null,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = KelmaDesktopColors.TextPrimary, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag),
            colors = SwitchDefaults.colors(checkedTrackColor = KelmaDesktopColors.Gold),
        )
    }
}

@Composable
private fun FixedSwitch(label: String, checked: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = KelmaDesktopColors.TextPrimary, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = null, enabled = false)
    }
}

@Composable
private fun DisabledOption(label: String) {
    Row(
        Modifier.fillMaxWidth().alpha(0.42f).semantics(mergeDescendants = true) { disabled() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = KelmaDesktopColors.TextSecondary, fontSize = 13.sp)
        Text("—", color = KelmaDesktopColors.TextMuted)
    }
}
