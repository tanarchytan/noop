package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noop.R
import com.noop.ai.AiProvider
import com.noop.ai.ChatMsg

/**
 * AI Coach, the single opt-in, bring-your-own-key feature.
 *
 * Two states:
 *  - No key saved → a setup card: masked key field, provider choice, model dropdown, Save, and a
 *    one-line privacy note.
 *  - Key saved → the chat: transcript of user/assistant bubbles, suggested-prompt chips, an input
 *    row with Send (disabled while sending), an error line in red, and a reset-key affordance.
 *
 * Everything is composed from the locked design system (ScreenScaffold / NoopCard / NoopType /
 * Palette / StatePill / SegmentedPillControl), dark Material3.
 */
@Composable
fun CoachScreen(vm: CoachViewModel = viewModel()) {
    val context = LocalContext.current
    val keyVersion by vm.keyVersion.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val customConnected by vm.customConnected.collectAsStateWithLifecycle()
    // Re-evaluate the gate whenever the stored key, provider, or custom-connect state changes.
    val configured = remember(keyVersion, provider, customConnected) { vm.isConfigured(context) }

    // No topBackground: the scaffold paints Palette.surfaceBase, the one canvas every screen shares. The
    // decorated backdrop it used to carry painted fixed dark-mode colours in both themes.
    ScreenScaffold(
        title = stringResource(R.string.coach_title),
        subtitle = stringResource(R.string.coach_subtitle),
    ) {
        if (!configured) {
            CoachSetup(vm = vm)
        } else {
            CoachChat(vm = vm)
        }
    }
}

// MARK: - Setup (no key saved)

@Composable
private fun CoachSetup(vm: CoachViewModel) {
    val context = LocalContext.current
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val availableModels by vm.availableModels.collectAsStateWithLifecycle()
    val refreshingModels by vm.refreshingModels.collectAsStateWithLifecycle()
    val customBaseUrl by vm.customBaseUrl.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }
    val isCustom = provider == AiProvider.CUSTOM

    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space18)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(Metrics.iconSmall))
                Text(
                    stringResource(R.string.coach_connect_provider),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            }
            Text(
                stringResource(
                    if (isCustom) R.string.coach_setup_body_custom else R.string.coach_setup_body_cloud,
                ),
                style = NoopType.subhead, color = Palette.textSecondary,
            )

            // Provider choice.
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Overline(stringResource(R.string.coach_provider))
                SegmentedPillControl(
                    items = AiProvider.entries,
                    selection = provider,
                    label = { it.shortName },
                    onSelect = { vm.selectProvider(context, it) },
                )
            }

            // Server URL, Custom (local LLM) only.
            if (isCustom) {
                val serverUrlLabel = stringResource(R.string.coach_server_url)
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Overline(serverUrlLabel)
                    OutlinedTextField(
                        value = customBaseUrl,
                        onValueChange = { vm.setCustomBaseUrl(context, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = serverUrlLabel },
                        placeholder = { Text("http://localhost:11434/v1", style = NoopType.body, color = Palette.textTertiary) },
                        textStyle = NoopType.mono(13f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = coachFieldColors(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }

            // Model dropdown + live-list refresh.
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Overline(stringResource(R.string.coach_model))
                    Spacer(Modifier.weight(1f))
                    RefreshModelsButton(
                        refreshing = refreshingModels,
                        // Cloud providers need a saved key to fetch; a local server just needs a URL.
                        enabled = if (isCustom) customBaseUrl.isNotBlank() else vm.hasKey(context),
                        onClick = { vm.refreshModels(context) },
                    )
                }
                ModelDropdown(
                    models = availableModels,
                    selected = model,
                    onSelect = { vm.selectModel(context, it) },
                )
            }

            // Masked key field, optional for a local Custom server.
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Overline(
                    stringResource(
                        if (isCustom) R.string.coach_api_key_optional else R.string.coach_api_key,
                    ),
                )
                CoachKeyField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = if (isCustom) {
                        stringResource(R.string.coach_key_placeholder_custom)
                    } else {
                        stringResource(R.string.coach_key_placeholder, provider.displayName)
                    },
                )
            }

            // Connect (Custom) / Save key (cloud).
            if (isCustom) {
                CoachPrimaryButton(
                    label = stringResource(R.string.coach_connect),
                    enabled = customBaseUrl.isNotBlank(),
                    onClick = {
                        if (keyInput.isNotBlank()) vm.saveKey(context, keyInput)
                        vm.connectCustom(context)
                    },
                )
            } else {
                CoachPrimaryButton(
                    label = stringResource(R.string.coach_save_key),
                    enabled = keyInput.isNotBlank(),
                    onClick = { vm.saveKey(context, keyInput) },
                )
            }

            // Privacy note, one line, always visible.
            PrivacyNote(local = isCustom)
        }
    }
}

// MARK: - Chat (key saved)

@Composable
private fun CoachChat(vm: CoachViewModel) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {

        // Active-provider strip + reset-key affordance.
        NoopCard(padding = 14.dp, tint = Palette.chargeColor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatePill(
                    title = stringResource(R.string.coach_provider_model, provider.displayName, model),
                    tone = StrandTone.Accent,
                    showsDot = true,
                )
                Spacer(Modifier.weight(1f))
                val disconnectInteraction = remember { MutableInteractionSource() }
                val disconnectLabel = stringResource(R.string.coach_disconnect_a11y)
                Text(
                    stringResource(R.string.coach_disconnect),
                    style = NoopType.caption,
                    color = Palette.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .liquidPress(disconnectInteraction)
                        .clickable(interactionSource = disconnectInteraction, indication = null) { vm.disconnect(context) }
                        .padding(horizontal = Metrics.space10, vertical = 6.dp)
                        .semantics { contentDescription = disconnectLabel },
                )
            }
        }

        // Data-access consent, off by default; no metrics are sent until this is on.
        val consent by vm.consent.collectAsStateWithLifecycle()
        NoopCard(padding = 14.dp, tint = Palette.chargeColor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text(
                        stringResource(R.string.coach_consent_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                    )
                    Text(
                        stringResource(
                            if (consent) R.string.coach_consent_on else R.string.coach_consent_off,
                        ),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = consent,
                    onCheckedChange = { vm.setConsent(context, it) },
                )
            }
        }

        // Editable system prompt, inline in the settings, collapsed by default. Edits persist and
        // take effect on the next message (the engine reads the stored prompt fresh per send).
        CoachInstructions(vm = vm)

        // Transcript or empty-state with suggested prompts.
        if (messages.isEmpty()) {
            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text(
                        stringResource(R.string.coach_empty_prompt),
                        style = NoopType.subhead, color = Palette.textSecondary,
                    )
                    SuggestedPrompts(onPick = { input = it })
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                messages.forEach { msg -> ChatBubble(msg) }
                if (sending) ThinkingBubble()
            }
        }

        // Error line (red).
        error?.let { message ->
            val errorLabel = stringResource(R.string.coach_error_a11y, message)
            Text(
                message,
                style = NoopType.subhead,
                color = Palette.statusCritical,
                modifier = Modifier.semantics { contentDescription = errorLabel },
            )
        }

        // Input row + Send, a frosted overlay surface so the composer reads as a docked input bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.surfaceOverlay)
                .border(Metrics.divider, Palette.hairline, RoundedCornerShape(18.dp))
                .padding(Metrics.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    if (error != null) vm.clearError()
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        stringResource(R.string.coach_input_placeholder),
                        style = NoopType.body,
                        color = Palette.textTertiary,
                    )
                },
                textStyle = NoopType.body,
                singleLine = false,
                maxLines = 4,
                enabled = !sending,
                colors = coachFieldColors(),
                shape = RoundedCornerShape(14.dp),
            )
            SendButton(
                enabled = input.isNotBlank() && !sending,
                sending = sending,
                onClick = {
                    vm.send(context, input)
                    input = ""
                },
            )
        }

        // Privacy note repeated under the input so it's always on screen.
        PrivacyNote(local = provider == AiProvider.CUSTOM)
    }
}

/**
 * Editable system prompt, the instructions that frame the coach. Collapsed by default; expanding
 * reveals a multi-line field bound to the view model (edits persist to [NoopPrefs] and take effect on
 * the next message) plus a Reset-to-default control. Inline in the settings, not a separate sheet.
 */
@Composable
private fun CoachInstructions(vm: CoachViewModel) {
    val context = LocalContext.current
    val prompt by vm.systemPrompt.collectAsStateWithLifecycle()
    val hasCustom by vm.hasCustomPrompt.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    val headerInteraction = remember { MutableInteractionSource() }
    val headerLabel = stringResource(
        if (expanded) R.string.coach_instructions_collapse else R.string.coach_instructions_edit,
    )
    val editorLabel = stringResource(R.string.coach_instructions_editor_a11y)
    NoopCard(padding = 14.dp, tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(if (expanded) 10.dp else 0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .liquidPress(headerInteraction)
                    .clickable(interactionSource = headerInteraction, indication = null) { expanded = !expanded }
                    .semantics { contentDescription = headerLabel },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text(
                        stringResource(R.string.coach_instructions_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                    )
                    Text(
                        stringResource(
                            if (hasCustom) R.string.coach_instructions_custom
                            else R.string.coach_instructions_default,
                        ),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = Palette.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (expanded) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { vm.setSystemPrompt(context, it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 260.dp)
                        .semantics { contentDescription = editorLabel },
                    textStyle = NoopType.body,
                    singleLine = false,
                    colors = coachFieldColors(),
                    shape = RoundedCornerShape(14.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { vm.resetSystemPrompt(context) },
                        enabled = hasCustom,
                    ) {
                        Text(
                            stringResource(R.string.coach_reset_default),
                            style = NoopType.footnote,
                            color = if (hasCustom) Palette.accent else Palette.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Chat bubbles

@Composable
private fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    val bubbleShape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        // User bubbles = a brand-green tinted bubble; Coach replies = a frosted Charge-tinted surface
        // so the reply reads as a card in the green Coach world rather than a flat grey box.
        val bubbleModifier = if (isUser) {
            Modifier
                .clip(bubbleShape)
                .background(Palette.accentMuted)
                .border(Metrics.divider, Palette.accent.copy(alpha = 0.35f), bubbleShape)
        } else {
            Modifier
                .clip(bubbleShape)
                .frostedCardSurface(tint = Palette.chargeColor, cornerRadius = 16.dp)
        }
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .then(bubbleModifier)
                .padding(horizontal = Metrics.space14, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Overline(
                    stringResource(
                        if (isUser) R.string.coach_speaker_you else R.string.coach_speaker_coach,
                    ),
                    color = if (isUser) Palette.accentHover else Palette.textTertiary,
                )
                if (isUser) {
                    Text(msg.text, style = NoopType.body, color = Palette.textPrimary)
                } else {
                    // Render the Coach's Markdown (bold/lists/headings) instead of raw symbols.
                    CoachMarkdown(msg.text, color = Palette.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    val thinkingLabel = stringResource(R.string.coach_thinking_a11y)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .frostedCardSurface(tint = Palette.chargeColor, cornerRadius = 16.dp)
                .padding(horizontal = Metrics.space14, vertical = 12.dp)
                .semantics { contentDescription = thinkingLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Palette.accent,
            )
            Text(stringResource(R.string.coach_thinking), style = NoopType.subhead, color = Palette.textSecondary)
        }
    }
}

// MARK: - Suggested prompts

/** The starter chips, as string resources so they are asked in the app's language. */
private val SUGGESTED_PROMPTS = listOf(
    R.string.coach_prompt_recovery_trend,
    R.string.coach_prompt_train_or_rest,
    R.string.coach_prompt_low_hrv,
    R.string.coach_prompt_improve_sleep,
)

@Composable
private fun SuggestedPrompts(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(stringResource(R.string.coach_try_asking))
        // Simple wrapped column of chips (one per row keeps long prompts readable).
        SUGGESTED_PROMPTS.forEach { promptRes ->
            val prompt = stringResource(promptRes)
            val chipLabel = stringResource(R.string.coach_suggested_prompt_a11y, prompt)
            val shape = RoundedCornerShape(50)
            val chipInteraction = remember { MutableInteractionSource() }
            Text(
                prompt,
                style = NoopType.caption,
                color = Palette.textPrimary,
                modifier = Modifier
                    .wrapContentWidth()
                    .clip(shape)
                    .background(Palette.surfaceInset)
                    .border(Metrics.divider, Palette.hairline, shape)
                    .liquidPress(chipInteraction)
                    .clickable(interactionSource = chipInteraction, indication = null) { onPick(prompt) }
                    .padding(horizontal = Metrics.space12, vertical = 8.dp)
                    .semantics { contentDescription = chipLabel },
            )
        }
    }
}

// MARK: - Model dropdown

@Composable
private fun ModelDropdown(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val triggerInteraction = remember { MutableInteractionSource() }
    val triggerLabel = stringResource(R.string.coach_model_a11y, selected)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Palette.surfaceInset)
                .border(Metrics.divider, Palette.hairline, shape)
                .liquidPress(triggerInteraction)
                .clickable(interactionSource = triggerInteraction, indication = null) { expanded = true }
                .padding(horizontal = Metrics.space14, vertical = 12.dp)
                .semantics { contentDescription = triggerLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Palette.textSecondary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Palette.surfaceOverlay),
        ) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(
                            m,
                            style = NoopType.body,
                            color = if (m == selected) Palette.accent else Palette.textPrimary,
                        )
                    },
                    onClick = {
                        onSelect(m)
                        expanded = false
                    },
                )
            }
            // Free-text escape hatch, any model id the provider accepts can be entered.
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.coach_model_custom),
                        style = NoopType.body,
                        color = Palette.textSecondary,
                    )
                },
                onClick = {
                    expanded = false
                    showCustom = true
                },
            )
        }
    }

    if (showCustom) {
        CustomModelDialog(
            initial = selected,
            onDismiss = { showCustom = false },
            onConfirm = { id ->
                showCustom = false
                if (id.isNotBlank()) onSelect(id)
            },
        )
    }
}

// MARK: - Custom model dialog (free-text id)

@Composable
private fun CustomModelDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val fieldLabel = stringResource(R.string.coach_custom_model_a11y)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Text(
                stringResource(R.string.coach_custom_model_title),
                style = NoopType.headline,
                color = Palette.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                Text(
                    stringResource(R.string.coach_custom_model_body),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = fieldLabel },
                    placeholder = {
                        Text(
                            stringResource(R.string.coach_custom_model_placeholder),
                            style = NoopType.body,
                            color = Palette.textTertiary,
                        )
                    },
                    textStyle = NoopType.mono(13f),
                    singleLine = true,
                    colors = coachFieldColors(),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.coach_use_model), style = NoopType.headline, color = Palette.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), style = NoopType.subhead, color = Palette.textSecondary)
            }
        },
    )
}

// MARK: - Refresh models (fetch live list)

@Composable
private fun RefreshModelsButton(
    refreshing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val active = enabled && !refreshing
    val refreshInteraction = remember { MutableInteractionSource() }
    val refreshLabel = stringResource(R.string.coach_fetch_models_a11y)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(Metrics.divider, Palette.hairline, shape)
            .let {
                if (active)
                    it
                        .liquidPress(refreshInteraction)
                        .clickable(interactionSource = refreshInteraction, indication = null, onClick = onClick)
                else it
            }
            .padding(horizontal = Metrics.space10, vertical = 6.dp)
            .semantics { contentDescription = refreshLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = Palette.accent)
        } else {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = if (active) Palette.accent else Palette.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            stringResource(if (refreshing) R.string.coach_fetching else R.string.coach_refresh_models),
            style = NoopType.caption,
            color = if (active) Palette.textPrimary else Palette.textTertiary,
        )
    }
}

// MARK: - Key field

@Composable
private fun CoachKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val fieldLabel = stringResource(R.string.coach_api_key_a11y)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = fieldLabel },
        placeholder = { Text(placeholder, style = NoopType.body, color = Palette.textTertiary) },
        textStyle = NoopType.mono(13f),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = coachFieldColors(),
        shape = RoundedCornerShape(14.dp),
    )
}

// MARK: - Buttons

@Composable
private fun CoachPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    // Disabled swaps both tokens: the accent at a low alpha kept a strong fill under a label that lost
    // far more contrast than it did, so the button read as pressable with an unreadable word on it.
    val bg = if (enabled) Palette.accent else Palette.surfaceInset
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(bg)
            .let {
                if (enabled)
                    it
                        .liquidPress(interaction)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else it
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = NoopType.headline,
            color = if (enabled) Palette.onFill else Palette.textTertiary,
        )
    }
}

@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Palette.accent else Palette.surfaceInset
    val interaction = remember { MutableInteractionSource() }
    val sendLabel = stringResource(R.string.coach_send_a11y)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(Metrics.divider, if (enabled) Color.Transparent else Palette.hairline, RoundedCornerShape(14.dp))
            .let {
                if (enabled)
                    it
                        .liquidPress(interaction)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else it
            }
            .semantics { contentDescription = sendLabel },
        contentAlignment = Alignment.Center,
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.accent)
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = if (enabled) Palette.surfaceBase else Palette.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// MARK: - Privacy note (one line)

@Composable
private fun PrivacyNote(local: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(13.dp))
        Text(
            stringResource(if (local) R.string.coach_privacy_local else R.string.coach_privacy_cloud),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

// MARK: - Shared field colors (dark, design-system tinted)

@Composable
private fun coachFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    disabledTextColor = Palette.textTertiary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    disabledBorderColor = Palette.hairline,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
    disabledContainerColor = Palette.surfaceInset,
)
