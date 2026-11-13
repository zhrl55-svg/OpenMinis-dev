package com.openminis.app.ui.chat.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.PickerModalityFilter
import com.openminis.app.ui.components.UnifiedModelPickerSheet

/**
 * [T-android-tts-capsule] Single-select voice OUTPUT (TTS) model picker — the
 * output twin, mirroring iOS UnifiedModelPicker(config: .voiceOutput()) which
 * the speech-player capsule's model chip opens. Selection writes
 * ProviderRepository.voiceOutputOverrideEntryId — the per-device override
 * slot resolveVoiceOutputChoice() consults first.
 */
@Composable
fun VoiceOutputPickerSheet(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val config by providerRepository.config.collectAsState()
    UnifiedModelPickerSheet(
        providerRepository = providerRepository,
        title = stringResource(R.string.tts_capsule_picker_title),
        modalityFilter = PickerModalityFilter.AUDIO_OUTPUT,
        boundGroup = config.voiceOutputGroupId?.let { gid ->
            config.modelGroups.find { it.id == gid }
        },
        boundGroupName = providerRepository.voiceOutputGroupName(),
        selectedId = providerRepository.voiceOutputOverrideEntryId,
        onSelect = { providerRepository.voiceOutputOverrideEntryId = it },
        onDismiss = onDismiss,
        onEditProvider = onEditProvider,
    )
}
