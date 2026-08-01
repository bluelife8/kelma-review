package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun MobileOptionsScreen(
    deckNames: List<String>,
    optionsByDeck: Map<String, DeckOptions>,
    syncing: Boolean,
    schedulerProfile: SchedulerProfileState = SchedulerProfileState(),
    studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy.systemDefault(),
    schedulerOptimizer: SchedulerOptimizerState = SchedulerOptimizerState(),
    deckPresets: DeckPresetState = DeckPresetState(),
    signedIn: Boolean = false,
    initialDeckName: String?,
    onDecks: () -> Unit,
    onBrowse: () -> Unit,
    onAdd: () -> Unit,
    onSyncLog: () -> Unit,
    onSyncNow: () -> Unit,
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
        mutableStateOf(initialDeckName?.takeIf { requested ->
            deckNames.any { it.equals(requested, ignoreCase = true) }
        } ?: deckNames.firstOrNull().orEmpty())
    }
    Scaffold(
        containerColor = KelmaColors.Background,
        topBar = {
            MobileOptionsTopBar(syncing, onSyncNow, onPlugins)
        },
        bottomBar = {
            MobileBottomNavigation(
                selected = MobileCollectionTab.Options,
                onDecks = onDecks,
                onBrowse = onBrowse,
                onAdd = onAdd,
                onOptions = {},
                onSyncLog = onSyncLog,
            )
        },
    ) { padding ->
        if (selectedDeck.isBlank()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Create a deck before configuring options", color = KelmaColors.TextSecondary)
            }
        } else {
            key(selectedDeck, optionsByDeck[selectedDeck], studyDayPolicy) {
                MobileDeckOptionsEditor(
                    modifier = Modifier.padding(padding),
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

@Composable
private fun MobileOptionsTopBar(
    syncing: Boolean,
    onSync: () -> Unit,
    onPlugins: () -> Unit,
) {
    Surface(modifier = Modifier.statusBarsPadding(), color = KelmaColors.Background) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Options", Modifier.weight(1f), color = KelmaColors.TextPrimary,
                fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            IconButton(onClick = onPlugins) {
                Icon(Icons.Rounded.Extension, contentDescription = "Plugins", tint = KelmaColors.GoldSoft)
            }
            IconButton(onClick = onSync, enabled = !syncing) {
                if (syncing) CircularProgressIndicator(Modifier.size(22.dp), color = KelmaColors.Gold, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Sync, contentDescription = "Sync now", tint = KelmaColors.GoldSoft)
            }
        }
    }
}

@Composable
private fun MobileDeckOptionsEditor(
    modifier: Modifier,
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
        val value = try {
            currentOptions()
        } catch (exception: Exception) {
            message = exception.message ?: "Check the option values"
            messageIsError = true
            return
        }
        saving = true
        scope.launch {
            val error = onSave(selectedDeck, value)
            saving = false
            message = error ?: "Options saved on this device"
            messageIsError = error != null
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .platformPointerScroll(scrollState)
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)
            .testTag("mobile-options-list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Deck options", color = KelmaColors.TextPrimary, fontSize = 30.sp,
            lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
        Text("Scheduling changes apply to this deck", Modifier.padding(top = 4.dp),
            color = KelmaColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        MobileDeckSelector(deckNames, selectedDeck, onSelectDeck)
        message?.let { text ->
            Text(text, color = if (messageIsError) KelmaColors.Bad else KelmaColors.Good,
                fontSize = 14.sp, lineHeight = 20.sp)
        }
        MobileOptionSection("Account study day") {
            MobileStudyDayPolicyControls(
                policy = studyDayPolicy,
                signedIn = signedIn,
                syncing = syncing,
                onSave = onSaveStudyDayPolicy,
            )
        }
        MobileOptionSection("Daily limits") {
            MobileOptionField("New cards per day", newLimit, { newLimit = it }, "mobile-options-new-limit")
            MobileOptionField("Maximum reviews per day", reviewLimit, { reviewLimit = it })
        }
        MobileOptionSection("Learning") {
            MobileOptionField("Learning steps", learningSteps, { learningSteps = it }, supporting = "For example: 1m 10m")
            MobileOptionField("Relearning step", relearningSteps, { relearningSteps = it }, supporting = "For example: 10m")
        }
        MobileOptionSection("Sibling burying") {
            MobileToggleOption("Bury new siblings", buryNewSiblings, "mobile-options-bury-new") {
                buryNewSiblings = it
            }
            MobileToggleOption("Bury review siblings", buryReviewSiblings, "mobile-options-bury-review") {
                buryReviewSiblings = it
            }
            MobileToggleOption(
                "Bury interday learning siblings",
                buryInterdaySiblings,
                "mobile-options-bury-interday",
            ) { buryInterdaySiblings = it }
        }
        MobileOptionSection("Audio and timer") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Automatically play audio", Modifier.weight(1f), color = KelmaColors.TextPrimary,
                    fontSize = 15.sp, lineHeight = 21.sp)
                Switch(autoplay, { autoplay = it })
            }
            MobileOptionField("Maximum answer seconds", answerSeconds, { answerSeconds = it })
        }
        MobileOptionSection("Review actions") {
            MobileToggleOption(
                "Confirm before undo",
                confirmBeforeUndo,
                "mobile-options-confirm-undo",
            ) { confirmBeforeUndo = it }
        }
        MobileOptionSection("Display order") {
            MobileEnumOption(
                "New card gather order", newGatherOrder, NewCardGatherOrder.entries,
                { it.label }, { newGatherOrder = it }, "mobile-options-new-gather-order",
            )
            MobileEnumOption(
                "New card sort order", newSortOrder, NewCardSortOrder.entries,
                { it.label }, { newSortOrder = it }, "mobile-options-new-sort-order",
            )
            MobileEnumOption(
                "New/review order", newMixOrder, QueueMixOrder.entries,
                { it.label }, { newMixOrder = it }, "mobile-options-new-mix-order",
            )
            MobileEnumOption(
                "Interday learning/review order", interdayMixOrder, QueueMixOrder.entries,
                { it.label }, { interdayMixOrder = it }, "mobile-options-interday-mix-order",
            )
            MobileEnumOption(
                "Review sort order", reviewSortOrder, ReviewSortOrder.entries,
                { it.label }, { reviewSortOrder = it }, "mobile-options-review-sort-order",
            )
        }
        MobileOptionSection(schedulerAlgorithm.label) {
            MobileOptionField("Desired retention (%)", retention, { retention = it }, "mobile-options-retention")
            MobileOptionField("FSRS parameters", parameters, { parameters = it },
                supporting = "${schedulerAlgorithm.parameterCount} comma-separated values", minLines = 3)
            MobileOptionField("Maximum interval (days)", maximumInterval, { maximumInterval = it })
            SchedulerOptimizerControls(
                state = schedulerOptimizer,
                signedIn = signedIn,
                enabled = schedulerAlgorithm == SchedulerAlgorithm.Fsrs6 && !syncing,
                compact = false,
                onStart = onStartOptimization,
                onCancel = onCancelOptimization,
                onApply = onApplyOptimizerCandidate,
                onDiscard = onDiscardOptimizerCandidate,
            )
            MobileAccountSchedulerProfileControls(
                state = schedulerProfile,
                signedIn = signedIn,
                enabled = schedulerAlgorithm == SchedulerAlgorithm.Fsrs6 && !syncing,
                onApplyCurrent = { publish ->
                    runCatching { onApplyAccountProfile(currentOptions(), publish) }
                        .getOrElse { it.message ?: "Check the scheduler profile values" }
                },
                onApplyCloud = onApplyCloudProfile,
            )
        }
        MobileOptionSection("Presets") {
            DeckPresetControls(
                state = deckPresets,
                deckName = selectedDeck,
                desktop = false,
                currentOptions = ::currentOptions,
                onAssign = onAssignPreset,
                onCreate = onCreatePreset,
                onClone = onClonePreset,
                onRename = onRenamePreset,
                onDelete = onDeletePreset,
            )
        }
        MobileOptionSection("Coming later", muted = true) {
            listOf("Easy days", "Auto advance", "Custom scheduling").forEach {
                Text(it, color = KelmaColors.TextMuted, fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
        Button(
            onClick = ::save,
            enabled = !saving && !syncing,
            modifier = Modifier.fillMaxWidth().testTag("mobile-options-save"),
        ) { Text(if (saving) "Saving…" else "Save options", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun MobileDeckSelector(names: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            Modifier.fillMaxWidth().clickable { expanded = true },
            color = KelmaColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected, Modifier.weight(1f), color = KelmaColors.TextPrimary, fontSize = 16.sp)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose deck", tint = KelmaColors.TextSecondary)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            names.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(name) })
            }
        }
    }
}

@Composable
private fun <T> MobileEnumOption(
    label: String,
    selected: T,
    choices: List<T>,
    choiceLabel: (T) -> String,
    onSelect: (T) -> Unit,
    tag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = KelmaColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        Box {
            Surface(
                Modifier.fillMaxWidth().clickable { expanded = true }.testTag(tag),
                color = KelmaColors.Background,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(choiceLabel(selected), Modifier.weight(1f), color = KelmaColors.TextPrimary, fontSize = 14.sp)
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = KelmaColors.TextSecondary)
                }
            }
            DropdownMenu(expanded, { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choiceLabel(choice)) },
                        onClick = { expanded = false; onSelect(choice) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileOptionSection(title: String, muted: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), color = KelmaColors.Surface, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = if (muted) KelmaColors.TextMuted else KelmaColors.GoldSoft,
                fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun MobileToggleOption(
    label: String,
    checked: Boolean,
    tag: String,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = KelmaColors.TextPrimary, fontSize = 15.sp, lineHeight = 21.sp)
        Switch(checked, onChecked, Modifier.testTag(tag))
    }
}

@Composable
private fun MobileOptionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String? = null,
    supporting: String? = null,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value, onValueChange,
        modifier = Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag)),
        label = { Text(label) },
        supportingText = supporting?.let { text -> { Text(text) } },
        minLines = minLines,
        shape = RoundedCornerShape(12.dp),
    )
}
