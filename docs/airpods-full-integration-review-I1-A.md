# Reviewer A — IMPLEMENTAZIONE / CORRETTIVO1 / I1 stabile

Modello effettivo: GPT-6.1 Sol, reasoning high (runtime della sessione). Reviewer indipendente, non autore; nessun subagente. Primo correttivo della fase, massimo tre; nessun reset storico.

Esito: non approvabile nel mio focus per uno scarto software P2. Nessun giudizio di completezza prodotto. Conformance FALSE corretta; H1 NV e indipendente dai voti software.

## Integrità, metodo e indipendenza

Congelato esclusivo: `C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable`. Manifest SHosservazione long-press56 `51627c5cb141cabccf85d95ca24f2430cf11bccbcd72c22d21e9bd0b5d198dac` verificato prima e dopo. Tutti 1166 file verificati per byte/hash; anche screenshot esterno privato verificato senza copiarlo/pubblicarlo. Tutti 133 file del sources-manifest upstream verificati per dimensioni, SHosservazione long-press56 e Git blob SHA1. Questa verifica lega le copie al manifest pinned; non è un fetch GitHub indipendente.

Letti piano, C1–C6, copertura, rubric e facts I1; consultato runtime della skill agentic-workflow, entrypoint fluid-engine e material3-expressive. Nessun AGENTS trovato negli antenati controllati o nel congelato. I vincoli espliciti escludono build/device suggeriti dalle skill. Nessuna memoria/rollout, review, voto o verdetto storico consultato. Usato solo changes-from-I0.json per delimitare il delta, senza assumere che i file invariati siano corretti. Incontrati esclusivamente dati parent e annotazioni storiche degli autori nei documenti obbligatori/licenza: non giudizi di altri reviewer.

Controlli miei: hash completi; verifica 11 mapping modelli/numeri/capability contro catalogo pinned; lettura dei percorsi descritti sotto; parsing XML; harness Java locale sulle classi codec congelate, 2946 asserzioni PASS (valori accettati, troncamenti, padding multibyte, signed head 0x44/0x45). Il primo javac del mio harness ha rifiutato il BOM UTF8 generato da PowerShell; corretto soltanto il file scratch, poi compilazione/esecuzione riuscite. Non è stata ricompilata l'app. Le classi selezionate non provano equivalenza dell'intero APK ai sorgenti.

Parent archiviato: XML estesi effettivamente letti e sommati: 67 suite,586 test,0 failure/error/skip. Storico core/Wear:33 suite,168 test,0 failure/error/skip; NON miei rerun né nuove esecuzioni I1. Letti corpi ProtocolDecodingTest, HostFacadeTest e sezioni dei test/implementazioni correttivi. I27 mirati e15 checker sono risultati parent: letto resoconto/log JSON, non rieseguiti né attribuiti a me. APK debug/minified/Wear corrispondono agli hash richiesti. Limiti: niente Gradle/build Android/device/ADB/radio/installazione/chiamata, niente lint nuovo o test Android indipendente. Screenshot non usati per certificare UI.

## Tabella richiesta

| Criterio | Voto 0–10 | Stato verifica | Motivazione/evidenze | Problemi / gravità |
|---|---:|---|---|---|
| I1 completezza | 7 | Verifica statica sostanziale, host codec eseguito; runtime NV | Riconciliazione19 controlli+16 famiglie,67 chiavi e41 disposizioni;11 modelli. Percorsi reali UI→host→controller→repository. Tile perde modi upstream | A1 P2 |
| I2 UI | NV | Lettura parziale dei percorsi; nessuna QA visuale mia | Signed-out/host/quick presenti nel wiring; geometria, temi, full-host e continuità decoder non verificati sufficientemente | NV non vale approvazione |
| I3 lifecycle | NV | Lettura parziale delta e guardie | Gate/start/lease/stop e nuove guardie esaminati, test host OFF letti; non verificata esaustivamente intera matrice C3/C4 | Approfondimento long-press non conclusivo; resto NV |
| I4 protocollo/stato | 8 | Sorgenti + classi congelate eseguite, fisico NV | Range/encoding, endian, padding, nullable battery5min, pending5s, close connect10s, generazioni; separazione modelDeclared/deviceAnnounced/propertyObserved | Nessun altro difetto concreto confermato nel perimetro controllato; ACK fisico NV |
| I5 automazioni | NV | Lettura mirata, non audit completo C5/C6 | Core media ora ricontrolla background per locali/esterni e comandi accodati; coordinator conserva ricevute e distingue correlazione C6. Non completata falsificazione di tutti overlap/manual override | Matrice runtime NV |
| I6 validazione | 8 | Integrità e XML verificati da me, build/checker parent |586 test negli XML coerenti,168 storici separati; limiti delle class_defs/JNI/R8 con gateFALSE dichiarati. Nessun PASS hardware/lint inventato | Non equivale a rebuild indipendente o release abilitabile |
| I7 confini/provenienza | 8 | Statica sostanziale |133 fonti pinned integre, attribuzione GPL, icona originale Canvas; PPE AACP distinto da ATT/vendor hook; ordinary A2DP reflection senza esenzioni/JNI/setAccessible; niente nuovi privilegi inventati per escludere disconnect | Fattibilità Android/calls/assistente ancora NV |
| I8 preservazione | NV | Lettura parziale wiring/startup |Shared host e startup conformance false, service e media separati; non audit esaustivo Spotify/auth/Wear/renderer/unrelated dirty work | Evidenze parent disponibili, non mia verifica completa |
| H1 fisico standalone | NV | Non eseguito, telefono indisponibile |Tre sessioni e readback/audio/ear/CA/gesti/background/cleanup restano obbligatori | Nessuna penalità hardware nei voti |

## A1 — Tile ridotta a due modi, P2

Trigger: modello con Adaptive (es. INFORMATION A3063), sessione ammessa e mode RX ANC; click Tile. Anche dopo più click non si raggiungono Adaptive né Off consentito.

Evidenza: `source/app/src/main/java/dev/lelonio/square/airpods/runtime/android/AirPodsAndroidRuntime.kt:171` calcola solo ANC/Trasparenza per disponibilità; `:177` emette soltanto3 o2. `AirPodsComponents.kt:81` inoltra al cycleTile. Upstream pinned `catalog/sources/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsQSService.kt:235` getAvailableModes include Off(se consentito),Trasparenza,Adaptive,ANC; `:246` avanza nell'intera lista. Il fatto che qs_click_behavior sia commentato non rende inattivo questo ciclo: onClick:155 sceglie cycle e chiama cycleAncMode.

Conseguenza: extra.qs è materialmente ridotta rispetto alla funzione Android attiva inclusa nel catalogo. Avere un enum Adaptive e la pagina completa non implementa quel comportamento della Tile. È deduzione diretta dai rami eseguiti, non test radio.

Fix minimo: condividere un selettore del prossimo modo applicabile con capability e ALLOW_OFF_OPTION correnti, e usare lo stesso risultato per disponibilità e dispatch. Non copiare il difetto upstream di proporre Adaptive su modelli che non lo supportano.

Regressione proporzionata: tabella Pro3/Pro1/unknown, Off consentito/vietato, RX mancante e pending; verificare sequenza intera dei comandi e assenza di write non ammessi. Nessuna necessità di redesign.

## Osservazione long-press: non difetto confermato

Il ciclo Noise Control da stem fisico (AirPodsAndroidRuntime.kt:338-366, collector:507) non usa le guardie background del ramo assistant. Tuttavia e' un comando hardware avviato da un gesto esplicito, mentre il requisito comune background governa certamente gli effetti media locali/esterni. Non assumo che debba sopprimere anche questa azione manuale, come non sopprime una Tile premuta. Non classifico questo come secondo difetto o motivo di voto: policy da rendere esplicita e testare, funzionamento fisico NV.

## Riconciliazione semantica

I19 controlli passano dal registry al PropertyEditor/QuickProperty, AirPodsHost.dispatch, controller.setProperty, repository e AacpCodec; STEM_CONFIG è dipendenza interna separata. Nessun reconnect replay rilevato. Range scalar e payload speciali sono reali: mic0..2; press/hold0..2; mask almeno2 modi; bool1/2; mode1..4 filtrato; chime0..100+[0x50]; swipe interval1..3; call[0,2]/[0,3]; Adaptive100-UI; PPE0x37 solo candidato Pro3; charge0x3b candidato finché RX. Pending per proprietà blocca doppio invio, timeout5s non cambia observed; RX other prevale senza ACK causale. Factory/connect e reader hanno generazioni; connect10s chiude il socket. Limite: i write bloccanti e scheduling Android non sono stati provati sul dispositivo.

Information conserva nome/modello/manufacturer/seriale/version1/version2/hardware/updater/serialiL-R/version3. Decoder richiede terminazioni e UTF8 valido, nessun fallback modello. UI mostra tutti11 campi. Battery4/2/8 separati, percent nullable per disconnesso/unknown, timestamp/generazione e freschezza5min; charging5 conserva percent, charging1 mostrato. Frequency reale RX non dimostrata. EQ non applica flat default aprendo pagina. Head STANDARD/ALTERNATE hanno start/stop distinti e pref per coppia; tracking serializza stop del formato attivo prima dello start nuovo; sample signed conforme a HeadOrientation pinned. Calling gestures instradano RINGING al port chiamate prima del media branch, con epoca/rilettura; esito API è solo richiesta e funzionalità fisica NV.

Le6 chiavi single/double/triple stem hanno lettori attivi upstream ma la relativa UI è commentata in AirPodsSettingsScreen; non le trasformo arbitrariamente in nuove35 famiglie. L'adattamento conserva i bit inferiori dello STEM_CONFIG; il runtime Fluidify gestisce soltanto long-press. Questo non prova supporto ai6 mapping latenti e merita un caso hardware con bit inferiori già attivi. Non classifico i getter locali/cache come stato device.

Di seguito gli ID sono elencati tutti; la colonna descrive il percorso/decisione verificato, non un PASS fisico individuale.

| Famiglia inclusa | Percorso / esito statico |
|---|---|
| aacp.mic_mode | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.double_click_interval | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.click_hold_interval | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.listening_mode_configs | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.one_bud_anc_mode | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.listening_mode | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.chime_volume | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.volume_swipe_interval | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.call_management_config | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.volume_swipe_mode | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.adaptive_volume_config | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.conversation_detect_config | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.auto_anc_strength | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.allow_off_option | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.sleep_detection_config | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.ear_detection_config | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.automatic_connection_config | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.ppe_toggle_config | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| aacp.dynamic_end_of_charge | PropertyEditor → dispatch Write → registry range/capability → serialized writer → matching property RX |
| device.rename | RenameEditor → host → rename → UTF8 length encoder → INFORMATION RX NAME; no optimistic name |
| device.battery | BatteryRows/Summary → liveBattery(4/2/8) → battery RX; freshness/generation |
| device.information | Information UI → requestInformation/bootstrap → parseInformation, 11 fields |
| device.eq | EqEditor explicit draft → setEq → codec0x63 → validated RX |
| host.volume | NumberEditor → LocalVolume → setVolume → MediaRuntimeCore.manualVolume → environment |
| host.ear_detection | local toggles → persisted HostSettings → live Ear RX → media core → coordinator C5 |
| host.ca | CONVERSATION device switch separate from activity0x4b → media conversation → coordinator |
| host.ca_pause | CA_PAUSE local → conversationPause → independent pause reasons |
| host.ca_relative | CA_RELATIVE local → VolumeMode.RELATIVE/ABSOLUTE |
| host.ca_volume | 10..85 local → conversationVolumePercent → volume ramp policy |
| host.head_gestures | HEAD_GESTURES/ALTERNATE_PACKETS → pair persistence → armTracking → head RX/detector → calls/media |
| host.long_press | GesturePicker L/R → setLongPress → observed STEM mask → stem RX → host branch; osservazione long-press |
| host.disconnect_unworn | toggle → live transition to NONE → OrdinaryAudioOperations with fresh authorization; physical NV |
| device.disconnect | Setup CONNECT/DISCONNECT → requestAudio → ordinary A2DP profile; Settings fallback explicit; submission not success |
| extra.qs | TileService → cycleTile → setProperty MODE; A1 |
| extra.diagnostics | collect/export/delete sanitized records; identity rendered only in explicit device UI |

###67 preferenze — destinazione semantica

Le chiavi cache/getter upstream non richiedono stesso nome né replica dei default. Mapping verificato per gruppi di comportamento; non ogni getter è un controllo attivo.

| Chiave | Destinazione/limite |
|---|---|
| CrossDeviceIsAvailable | globale/business/setup LP non duplicato; sistemi Fluidify e consenso esplicito, nessun nuovo RX |
| adaptive_strength | AUTO_ANC_STRENGTH RX + draft invertito; nessun replay51 |
| airpods_hardware_revision | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_left_serial_number | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_manufacturer | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_model_number | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_name | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_right_serial_number | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_serial_number | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_updater_identifier | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_version1 | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_version2 | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| airpods_version3 | campo Information corrente; cache non diventa RX; identificativi redatti dai log |
| automatic_connection_ctrl_cmd | AUTOMATIC_CONNECTION_CONFIG bool1/2 |
| automatic_ear_detection | EAR_DETECTION_CONFIG hardware distinto da host earPause/earResume |
| bypass_device_check.v2 | escluso root/Xposed/developer bypass |
| camera_action | camera latente, navigazione commentata |
| connection_successful | PairVerification da bootstrap reale; non cache bool |
| conversation_awareness | host conversationDuck; hardware CONVERSATION separato |
| conversation_awareness_pause_music | host conversationPause |
| conversation_awareness_volume | conversationVolumePercent10..85 |
| custom_camera_package | camera latente, navigazione commentata |
| disconnect_when_not_wearing | host.disconnectUnworn ordinary profile best effort |
| dynamic_end_of_charge | DYNAMIC_END_OF_CHARGE RX candidato, non extra.global soltanto |
| first_connection_successful_time | setup evidence timestamp; non capability |
| foss_upgraded | globale/business/setup LP non duplicato; sistemi Fluidify e consenso esplicito, nessun nuovo RX |
| head_gestures | host.headGestures; consenso generale/background separati |
| left_double_press_action | mapping stem latente: getter attivo, editor Settings commentato; nessun controllo prodotto aggiunto |
| left_long_press_action | PairSettings.leftLongPress → STEM_CONFIG/host; osservazione long-press |
| left_single_press_action | mapping stem latente: getter attivo, editor Settings commentato; nessun controllo prodotto aggiunto |
| left_triple_press_action | mapping stem latente: getter attivo, editor Settings commentato; nessun controllo prodotto aggiunto |
| long_press_adaptive | bit8 LISTENING_MODE_CONFIGS con ADAPTIVE_AUDIO |
| long_press_byte | LISTENING_MODE_CONFIGS RX, non STEM_CONFIG |
| long_press_nc | bit2 LISTENING_MODE_CONFIGS |
| long_press_off | bit1 LISTENING_MODE_CONFIGS con Off consentito |
| long_press_transparency | bit4 LISTENING_MODE_CONFIGS |
| loud_sound_reduction | nessuna replica cache; PPE/allowOff distinti da ATT hearing |
| m3e_enabled | globale/business/setup LP non duplicato; sistemi Fluidify e consenso esplicito, nessun nuovo RX |
| mac_address | selectedPair privato; label non certifica setup |
| name | rename RX/device UI; non riprodurre tutte le collisioni lexicali catalogo |
| off_listening_mode | ALLOW_OFF_OPTION RX; filtra MODE e cycle mask |
| onboarding_complete | globale/business/setup LP non duplicato; sistemi Fluidify e consenso esplicito, nessun nuovo RX |
| personalized_volume | ADAPTIVE_VOLUME_CONFIG RX |
| premium_expiry_time | globale/business/setup LP non duplicato; sistemi Fluidify e consenso esplicito, nessun nuovo RX |
| qs_click_behavior | getter UI commentato; cycle attivo, A1 |
| relative_conversation_awareness_volume | conversationRelative |
| review_prompted | globale/business/setup LP non duplicato; sistemi Fluidify e consenso esplicito, nessun nuovo RX |
| right_double_press_action | mapping stem latente: getter attivo, editor Settings commentato; nessun controllo prodotto aggiunto |
| right_long_press_action | PairSettings.rightLongPress → STEM_CONFIG/host; osservazione long-press |
| right_single_press_action | mapping stem latente: getter attivo, editor Settings commentato; nessun controllo prodotto aggiunto |
| right_triple_press_action | mapping stem latente: getter attivo, editor Settings commentato; nessun controllo prodotto aggiunto |
| self_mac_address | infrastruttura LP/takeover non replicata |
| show_bottom_sheet_popup | extra.popups globale LP escluso; non configurazione cuffie |
| show_island_popup | extra.popups globale LP escluso; non configurazione cuffie |
| show_phone_battery_in_widget | escluso widget |
| single_anc | ONE_BUD_ANC_MODE RX |
| skip_setup | globale/business/setup LP non duplicato; sistemi Fluidify e consenso esplicito, nessun nuovo RX |
| takeover_when_call | escluso takeover per vincolo utente |
| takeover_when_disconnected | escluso takeover per vincolo utente |
| takeover_when_idle | escluso takeover per vincolo utente |
| takeover_when_media_start | escluso takeover per vincolo utente |
| takeover_when_music | escluso takeover per vincolo utente |
| takeover_when_ringing_call | escluso takeover per vincolo utente |
| tone_volume | CHIME_VOLUME RX payload[value,0x50], non ATT tone |
| use_alternate_head_tracking_packets | headPacketAlternate per coppia, defaulttrue C1 |
| vendor_id_hook | escluso root/Xposed/developer bypass |
| volume_control | VOLUME_SWIPE_MODE RX |

###41 disposizioni/esclusioni

Protocol-only: nessun editor Android attivo provato dai soli enum; non dichiara hardware impossibile. ATT: dipendenza vendorIdHook/hearing dal percorso pinned, distinta dal PPE AACP incluso. Camera: navigazione commentata. Nessun trasferimento di queste esclusioni a funzioni ordinarie Android.

| ID | Decisione |
|---|---|
| aacp.button_send_mode | protocol_only |
| aacp.voice_trigger | protocol_only |
| aacp.single_click_mode | protocol_only |
| aacp.double_click_mode | protocol_only |
| aacp.click_hold_mode | protocol_only |
| aacp.crown_rotation_direction | protocol_only |
| aacp.auto_answer_mode | protocol_only |
| aacp.software_mute_config | protocol_only |
| aacp.ssl | protocol_only |
| aacp.hearing_aid | excluded_xposed_path |
| aacp.hps_gain_swipe | excluded_xposed_path |
| aacp.hrm_state | protocol_only |
| aacp.in_case_tone_config | protocol_only |
| aacp.siri_multitone_config | protocol_only |
| aacp.hearing_assist_config | excluded_xposed_path |
| aacp.stem_config | internal_dependency |
| aacp.allow_auto_connect | protocol_only |
| aacp.owns_connection | excluded_takeover |
| aacp.ppe_cap_level_config | protocol_only |
| att.transparency.amplification | excluded_xposed_path |
| att.transparency.balance | excluded_xposed_path |
| att.transparency.tone | excluded_xposed_path |
| att.transparency.ambient_noise_reduction | excluded_xposed_path |
| att.transparency.conversation_boost | excluded_xposed_path |
| att.hearing.amplification | excluded_xposed_path |
| att.hearing.balance | excluded_xposed_path |
| att.hearing.tone | excluded_xposed_path |
| att.hearing.ambient_noise_reduction | excluded_xposed_path |
| att.hearing.conversation_boost | excluded_xposed_path |
| att.transparency.enabled | excluded_xposed_path |
| att.hearing.audiogram | excluded_xposed_path |
| aacp.accommodation | developer_or_latent |
| att.transparency.eq | excluded_xposed_path |
| extra.case | protocol_only |
| extra.hrm | protocol_only |
| extra.camera | excluded_latent |
| extra.widget | excluded_widget |
| extra.popups | excluded_global |
| extra.global | excluded_global |
| extra.root | excluded_root_xposed |
| extra.takeover | excluded_takeover |

## Limiti e integrità finale

Non ho completato una falsificazione esaustiva di I2/I3/I5/I8: NV esplicito, nessun voto sostitutivo o media di completezza. In particolare nessuna misura ottica full-host, prova reale chiamate, background Android o Bluetooth. Nessuna modifica di implementazione/design, sorgenti live o congelati; soltanto scratch I1-A. Lo snapshot non è un repo interamente ricostruibile. R8 con conformanceFALSE può rimuovere percorsi: la sola classe JNI mantenuta non dimostra gli altri; un futuro APK abilitato richiede nuova verifica.

Audit finale rieseguito:1166/1166 dimensioni e SHosservazione long-press56 corretti, screenshot esterno corretto,133/133 fonti pinned corrette, manifest invariato. Artefatti della review: CodecAudit.java/.class, audit.py, audit-results.json e questo report; tutti sotto reviewer-scratch/I1-A.
