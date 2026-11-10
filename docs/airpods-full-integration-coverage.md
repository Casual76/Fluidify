# Copertura funzionale AirPods ? riferimento F1

Catalogo canonico: `build/airpods-full-integration/catalog/catalog.json`, commit LibrePods
`89356c08cea3db0b6b54792d9329d09a87cc85fa`. Questa tabella definisce lavoro richiesto,
non implementazioni gi? fatte o supporto hardware provato. Tutte le funzioni fisiche sono NV.

Il catalogo comprende38 identificatori AACP e38 funzioni supplementari: 35 voci
incluse/adattate e41 voci protocollo/infrastruttura/escluse/latenti, non76 impostazioni utente.
Le67 chiavi e227 stringhe UI riconciliate sono copertura lessicale, non prova di parit? funzionale.
Le fonti complete, tutte le occorrenze e le dipendenze per voce sono nel JSON, non solo nei riferimenti abbreviati qui.

## Voci incluse o da adattare

Per ciascuna voce serve implementazione/entry point, test pertinente e risultato del gate applicabile.
Il campo dispositivo non viene valorizzato dal solo default. Best effort/NV non equivale a funziona.

| ID | Gruppo e opzione | Valori / contratto | Capacit? e limiti | Fonti abbreviate |
|---|---|---|---|---|
| aacp.mic_mode | microphone ? Microphone side | 0 automatic, 1 right, 2 left | model gate absent | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:89; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AirPodsSettingsScreen.kt:504 |
| aacp.double_click_interval | accessibility ? Press speed | 0 default, 1 slower, 2 slowest | no model gate in UI | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:92; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:137 |
| aacp.click_hold_interval | accessibility ? Hold duration | 0 default, 1 slower, 2 slowest (upstream labels) | no model gate in UI | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:92; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:153 |
| aacp.listening_mode_configs | gestures ? Modes cycled on long press | bitmask Off=1, ANC=2, Transparency=4, Adaptive=8; preserve at least 2 modes | STEM_CONFIG; per-mode capability gate needed | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:92; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/PressAndHoldSettingsScreen.kt:70 |
| aacp.one_bud_anc_mode | accessibility ? ANC with one bud | bool 1 on, 2 off | UI lacks LISTENING_MODE gate; validate model | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:95; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:248 |
| aacp.listening_mode | audio ? Listening mode | 1 Off, 2 ANC, 3 Transparency, 4 Adaptive | LISTENING_MODE; Adaptive needs ADAPTIVE_AUDIO | android/app/src/main/java/me/kavishdevar/librepods/QuickSettingsDialogActivity.kt:498; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:92 |
| aacp.chime_volume | volume ? Tone volume | 0..100; payload [value,0x50] | no explicit model gate | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:98; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:282 |
| aacp.volume_swipe_interval | volume ? Volume swipe speed | 1 default, 2 longer, 3 longest | SWIPE_FOR_VOLUME | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:98; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:169 |
| aacp.call_management_config | calls ? Call mute/unmute and end gesture | single vs double press, complementary assignment; wire [0,2] flipped / [0,3] normal (AppNavGraph:243-249) | no capability gate upstream | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:98; android/app/src/main/java/me/kavishdevar/librepods/presentation/navigation/AppNavGraph.kt:244 |
| aacp.volume_swipe_mode | volume ? Swipe volume control | bool 1 on, 2 off | SWIPE_FOR_VOLUME | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:98; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:314 |
| aacp.adaptive_volume_config | audio ? Personalized volume | bool 1 on, 2 off | ADAPTIVE_VOLUME | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:101; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AirPodsSettingsScreen.kt:450 |
| aacp.conversation_detect_config | audio ? Conversation awareness device switch | bool 1 on, 2 off | CONVERSATION_AWARENESS | android/app/src/main/java/me/kavishdevar/librepods/QuickSettingsDialogActivity.kt:579; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:101 |
| aacp.auto_anc_strength | audio ? Adaptive audio strength | UI 0..100, wire=100-UI; debounce 150ms | upstream main UI incorrectly tests ADAPTIVE_VOLUME; require ADAPTIVE_AUDIO | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:104; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AdaptiveStrengthScreen.kt:94 |
| aacp.allow_off_option | audio ? Allow Off listening mode | bool 1 on, 2 off; also local off_listening_mode | LOUD_SOUND_REDUCTION upstream visibility | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:107; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AirPodsSettingsScreen.kt:567 |
| aacp.sleep_detection_config | automation ? Pause when falling asleep | bool 1 on, 2 off | SLEEP_DETECTION; firmware-dependent | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:110; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AirPodsSettingsScreen.kt:523 |
| aacp.ear_detection_config | automation ? Automatic ear detection device setting | bool 1 on, 2 off plus host pause/resume policy | no model gate upstream; RX required | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:111; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:447 |
| aacp.automatic_connection_config | connection ? Automatic connection device configuration | bool 1 on, 2 off; semantics not equal host takeover | no model gate upstream | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:111; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:448 |
| aacp.ppe_toggle_config | hearing ? Hearing protection / workspace PPE | bool 1 on, 2 off | PPE (Pro3 static table) | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:111; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingProtectionScreen.kt:106 |
| aacp.dynamic_end_of_charge | charging ? Optimized charging | bool 1 on, 2 off; cached preference | no model gate upstream; support unknown until relevant RX | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:115; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:184 |
| device.rename | identity ? Rename AirPods | text UTF-8; no confirmed device length range | no explicit gate | android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:629; android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt:3090 |
| device.battery | status ? Left/right/case battery and charging | component, percent, charging/disconnected status | model/component dependent | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:232; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:420 |
| device.information | identity ? Model, name, serials, versions, hardware revision/updater identifier | strings from INFORMATION | AirPodsModels table | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:164; android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt:232 |
| device.eq | audio ? Custom EQ | enabled state=2, disabled=1; low/mid/high 0..100 | customEqCapability=true in UI; no actual universal support proof | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:25; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:205 |
| host.volume | volume ? Quick settings media volume | STREAM_MUSIC integer 0..getStreamMaxVolume; normalized slider | Android media volume, no model capability | android/app/src/main/java/me/kavishdevar/librepods/QuickSettingsDialogActivity.kt:328; android/app/src/main/java/me/kavishdevar/librepods/QuickSettingsDialogActivity.kt:330 |
| host.ear_detection | automation ? Ear removal pause / insertion resume | explicit Fluidify opt-in; preserve manual media changes | ear RX, not model name alone | android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt:904; android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt:1233 |
| host.ca | automation ? Conversation start/end attenuation | RX statuses 1/2 start, 6/8/9 stop; stream MUSIC attenuation | CONVERSATION_AWARENESS RX | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:234; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:483 |
| host.ca_pause | automation ? Pause/resume during conversation | on/off conversation_awareness_pause_music | CA host enabled | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:226; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:227 |
| host.ca_relative | automation ? Relative conversation volume | relative percentage vs absolute STREAM_MUSIC cap | CA host enabled | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:234; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:235 |
| host.ca_volume | automation ? Conversation target volume | UI 10..85 percent; snap 44 | CA host enabled | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:234; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:235 |
| host.head_gestures | gestures ? Head gestures and live tracking plot | toggle nod/shake actions; head tracking start/stop; velocity plot; advanced alternate packet format selector, persisted per verified pair | HEAD_GESTURES | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AirPodsSettingsScreen.kt:537; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AirPodsSettingsScreen.kt:539 |
| host.long_press | gestures ? Left/right long-press action | CYCLE_NOISE_CONTROL_MODES or DIGITAL_ASSISTANT | STEM_CONFIG; assistant needs recent firmware per upstream | android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:312; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:484 |
| host.disconnect_unworn | automation ? Disconnect audio when not wearing | disconnect_when_not_wearing on/off | profile manipulation platform-dependent | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:285; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:286 |
| device.disconnect | connection ? Disconnect/reconnect manual control | stop own session, reconnect saved device | exclusive owner | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:1272; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/ATTManager.kt:141 |
| extra.qs | extras ? Android Quick Settings tile behavior | active onClick hardcodes cycle ANC; dialog branch exists; qs_click_behavior getter is commented | LISTENING_MODE for ANC | android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsQSService.kt:155; android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt:492 |
| extra.diagnostics | diagnostics ? Diagnostics/log collection | list/view/share/delete logs; support state; reconnect troubleshooting | not hardware settings | README.md:136; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/TroubleshootingScreen.kt:99 |

## Voci non esposte come impostazioni ordinarie

`protocol_only` non significa firmware impossibile: non ? dimostrato un editor Android attivo.
`internal_dependency` deve supportare le funzioni incluse senza pagina arbitraria.
`excluded_xposed_path` vale per la dipendenza Android pinned, non per tutti i firmware.
Le esclusioni complete sono in `omissions-exclusions.json`; i chiarimenti root in `orchestration/integration-decisions.md`.

| ID | Nome | Classificazione | Dipendenza/ambito | Fonti abbreviate |
|---|---|---|---|---|
| aacp.button_send_mode | Button Send Mode | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:89 |
| aacp.voice_trigger | Voice Trigger | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:89 |
| aacp.single_click_mode | Single Click Mode | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:89 |
| aacp.double_click_mode | Double Click Mode | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:89 |
| aacp.click_hold_mode | Click Hold Mode | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:92 |
| aacp.crown_rotation_direction | Crown Rotation Direction | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:95 |
| aacp.auto_answer_mode | Auto Answer Mode | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:95; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:432 |
| aacp.software_mute_config | Software Mute Config | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:101 |
| aacp.ssl | Ssl | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:104 |
| aacp.hearing_aid | Hearing aid enrollment/enable | excluded_xposed_path | HEARING_AID plus vendorIdHook route gate | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:104; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/ATTManager.kt:32 |
| aacp.hps_gain_swipe | Swipe hearing amplification | excluded_xposed_path | hearing adjustments require ATT and vendorIdHook route | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:104; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidAdjustmentsScreen.kt:174 |
| aacp.hrm_state | Hrm State | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:104; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:178 |
| aacp.in_case_tone_config | In Case Tone Config | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:107 |
| aacp.siri_multitone_config | Siri Multitone Config | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:107 |
| aacp.hearing_assist_config | Hearing assist configuration | excluded_xposed_path | HEARING_AID plus vendorIdHook route gate | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:107; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidScreen.kt:91 |
| aacp.stem_config | Stem configuration transport | internal_dependency | STEM_CONFIG | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:110; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:1135 |
| aacp.allow_auto_connect | Allow Auto Connect | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:110; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:446 |
| aacp.owns_connection | Ownership signal | excluded_takeover | not a user control | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:111; android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:226 |
| aacp.ppe_cap_level_config | Ppe Cap Level Config | protocol_only | no support inference from enum | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:114; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:183 |
| att.transparency.amplification | transparency amplification | excluded_xposed_path | vendorIdHook and !hearingAidEnabled | README.md:119; android/app/src/main/java/me/kavishdevar/librepods/data/HearingAid.kt:119 |
| att.transparency.balance | transparency balance | excluded_xposed_path | vendorIdHook and !hearingAidEnabled | README.md:119; android/app/src/main/java/me/kavishdevar/librepods/data/HearingAid.kt:48 |
| att.transparency.tone | transparency tone | excluded_xposed_path | vendorIdHook and !hearingAidEnabled | README.md:119; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:282 |
| att.transparency.ambient_noise_reduction | transparency ambient_noise_reduction | excluded_xposed_path | vendorIdHook and !hearingAidEnabled | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidAdjustmentsScreen.kt:205; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/TransparencySettingsScreen.kt:245 |
| att.transparency.conversation_boost | transparency conversation_boost | excluded_xposed_path | vendorIdHook and !hearingAidEnabled | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidAdjustmentsScreen.kt:217; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidAdjustmentsScreen.kt:220 |
| att.hearing.amplification | hearing amplification | excluded_xposed_path | HEARING_AID and vendorIdHook route | README.md:119; android/app/src/main/java/me/kavishdevar/librepods/data/HearingAid.kt:119 |
| att.hearing.balance | hearing balance | excluded_xposed_path | HEARING_AID and vendorIdHook route | README.md:119; android/app/src/main/java/me/kavishdevar/librepods/data/HearingAid.kt:48 |
| att.hearing.tone | hearing tone | excluded_xposed_path | HEARING_AID and vendorIdHook route | README.md:119; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:282 |
| att.hearing.ambient_noise_reduction | hearing ambient_noise_reduction | excluded_xposed_path | HEARING_AID and vendorIdHook route | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidAdjustmentsScreen.kt:205; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/TransparencySettingsScreen.kt:245 |
| att.hearing.conversation_boost | hearing conversation_boost | excluded_xposed_path | HEARING_AID and vendorIdHook route | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidAdjustmentsScreen.kt:217; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidAdjustmentsScreen.kt:220 |
| att.transparency.enabled | Customized transparency enabled | excluded_xposed_path | vendorIdHook; hearing aid disabled | android/app/src/main/java/me/kavishdevar/librepods/data/Transparency.kt:144; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidScreen.kt:64 |
| att.hearing.audiogram | Update hearing test values | excluded_xposed_path | HEARING_AID plus ATT vendorIdHook path | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/UpdateHearingTestScreen.kt:102; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/UpdateHearingTestScreen.kt:299 |
| aacp.accommodation | Headphone accommodation / phone-media EQ | developer_or_latent | no verified model support | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:1178; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AccessibilitySettingsScreen.kt:194 |
| att.transparency.eq | Transparency eight-band EQ | excluded_xposed_path | vendorIdHook and !hearingAidEnabled | android/app/src/main/java/me/kavishdevar/librepods/data/Transparency.kt:144; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HearingAidScreen.kt:64 |
| extra.case | Charging case sound / find case | protocol_only | no per-model support established | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:107 |
| extra.hrm | Heart rate monitoring | protocol_only | HRM static Pro3 only | android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:104; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:178 |
| extra.camera | Camera remote | excluded_latent | STEM_CONFIG; active navigation commented | android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:235; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:242 |
| extra.widget | Battery and ANC widgets; phone battery | excluded_widget | not hardware capability | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:199; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:200 |
| extra.popups | Island / bottom sheet popups | excluded_global | SYSTEM_ALERT_WINDOW | android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:215; android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/AppSettingsScreen.kt:216 |
| extra.global | Appearance, billing, onboarding, release notes and distribution | excluded_global | app distribution not AirPods firmware | android/app/src/main/java/me/kavishdevar/librepods/MainActivity.kt:84; android/app/src/main/java/me/kavishdevar/librepods/MainActivity.kt:88 |
| extra.root | Root/Xposed support plumbing | excluded_root_xposed | root/Xposed/system modifications | android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AirPodsViewModel.kt:494; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AppSettingsViewModel.kt:151 |
| extra.takeover | Takeover / smart routing | excluded_takeover | vendor spoof + profile APIs | android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AppSettingsViewModel.kt:142; android/app/src/main/java/me/kavishdevar/librepods/presentation/viewmodel/AppSettingsViewModel.kt:143 |

## Modelli dichiarati upstream

Il mapping statico non ? una prova su ogni modello/firmware n? autorizza fallback Pro3.
Unknown rimane Unknown; capability pubblicizzata, percorso consentito e RX attuale sono distinti.

| ID modello upstream | Identificatori | Capability dichiarate |
|---|---|---|
| AirPods | A1523, A1722 |  |
| AirPods2 | A2032, A2031 |  |
| AirPods3 | A2565, A2564 | HEAD_GESTURES |
| AirPods4 | A3053, A3050, A3054 | HEAD_GESTURES, SLEEP_DETECTION, ADAPTIVE_VOLUME |
| AirPods4ANC | A3056, A3055, A3057 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, ADAPTIVE_AUDIO, SLEEP_DETECTION, ADAPTIVE_VOLUME, STEM_CONFIG |
| AirPods5 | A3531, A3532, A3533 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, ADAPTIVE_AUDIO, SLEEP_DETECTION, ADAPTIVE_VOLUME, STEM_CONFIG |
| AirPods5WCC | A3439, A3440, A3441 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, ADAPTIVE_AUDIO, SLEEP_DETECTION, ADAPTIVE_VOLUME, STEM_CONFIG, SWIPE_FOR_VOLUME |
| AirPodsPro1 | A2084, A2083 | LISTENING_MODE |
| AirPodsPro2Lightning | A2931, A2699, A2698 | LISTENING_MODE, CONVERSATION_AWARENESS, STEM_CONFIG, LOUD_SOUND_REDUCTION, SLEEP_DETECTION, HEARING_AID, ADAPTIVE_AUDIO, ADAPTIVE_VOLUME, SWIPE_FOR_VOLUME, HEAD_GESTURES |
| AirPodsPro2USBC | A3047, A3048, A3049 | LISTENING_MODE, CONVERSATION_AWARENESS, STEM_CONFIG, LOUD_SOUND_REDUCTION, SLEEP_DETECTION, HEARING_AID, ADAPTIVE_AUDIO, ADAPTIVE_VOLUME, SWIPE_FOR_VOLUME, HEAD_GESTURES |
| AirPodsPro3 | A3063, A3064, A3065 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, STEM_CONFIG, LOUD_SOUND_REDUCTION, PPE, SLEEP_DETECTION, HEARING_AID, ADAPTIVE_AUDIO, ADAPTIVE_VOLUME, SWIPE_FOR_VOLUME, HRM |

## Casi da risolvere esplicitamente nell?implementazione

- Gesti testa: il percorso LP cerca takeover quando non possiede la connessione. Takeover escluso;
  verificare ricezione/tracking nel normale uso esclusivo e lasciare il supporto NV fino a prova specifica.
- Disconnect quando non indossate: adattatore ordinario best effort da verificare; niente setConnectionPolicy
  o permessi privilegiati. Se non accessibile, stato non disponibile per la piattaforma con motivazione.
- Tile Android: inclusa come client dello stesso owner; non equivale alla capsula Home o al widget LP.
- EQ: tre bande e stato device con RX/Unknown. Non eseguire l?effetto phone/media nascosto nell?onboarding
  Accessibility quando il relativo editor ? commentato.
- Hearing aid/trasparenza personalizzata/ATT: escluse per vendorIdHook/Xposed nel percorso Android pinned;
  PPE AACP rimane incluso. Non trasformare la sola tabella capacit? Pro3 in supporto di tutti questi percorsi.
- Camera, HRM e suoni/find-case senza percorso Settings Android attivo restano voci latenti/protocollo,
  senza inventare controlli o completamento. Billing e aggiornamento app LP usano i sistemi Fluidify esistenti.

## Registro di avanzamento richiesto

Prima della consegna ogni ID incluso deve avere stato implementato/testato e prove riferite al codice corrente,
oppure un limite piattaforma concretamente dimostrato con presentazione coerente. Hardware NV resta aperto;
una pagina vuota, un toggle solo locale o un generico ?non supportato? senza evidenza non chiudono la voce.
Le preferenze locali effettive sono testate a parte e non richiedono RX per confermare il loro salvataggio.

F1 precisa la preferenza attiva use_alternate_head_tracking_packets sotto host.head_gestures:
opzione avanzata per coppia, default true, formati start/stop accoppiati e test C1
in airpods-full-integration-contracts.md. La mappatura semantica ? esplicita, non solo lessicale.
# Disposizione esplicita delle preferenze stem residue (I3)

Le sei chiavi `left/right_single/double/triple_press_action` restano nel catalogo
delle 67 preferenze. Al pin Android `89356c08cea3db0b6b54792d9329d09a87cc85fa`
hanno consumer attivi (config, listener, STEM mask e dispatch), ma il percorso di
scrittura individuato imposta solo il default quando la chiave manca. Il corpus
di 108/108 sorgenti Android Kotlin/Java e gli ingressi del prodotto non mostrano
un editor o contratto per personalizzarle; il setter dinamico della UI riguarda
soltanto long press. I valori ripristinati o modificati manualmente possono invece
essere letti dai consumer: non si dichiarano chiavi inutilizzate.

La classificazione è quindi **personalizzazione latente, senza nuova UI
obbligatoria**, distinta da esclusione per privilegi o da impossibilità firmware.
Non aggiunge controlli, elimina famiglie o importa preferenze private di LibrePods.
Fluidify non rimappa questi tre gesti e conserva i bit non posseduti della STEM
mask; il comportamento fisico resta da verificare. Long press rimane incluso.

Il default inizializzato per doppia pressione è NEXT_TRACK; il fallback assente
del getter/listener sinistro upstream è incoerentemente PREVIOUS_TRACK. Questi
fallback non sono RX e non vengono copiati come un nuovo comportamento hardware.
La prova circoscritta, gli ingressi controllati e i limiti sono nel dossier
`build/airpods-full-integration/implementation/corrective-I3-stem-disposition/`;
`functional_disposition` è riprodotto dal generatore del catalogo. Per gli hash
interni correnti usare `catalog/artifact-manifest-I3.json`: quelli precedenti
sono storici e conservati, non verificatori del catalogo attuale. Nessun PASS
hardware o riduzione delle 35 famiglie obbligatorie deriva da questa nota.

