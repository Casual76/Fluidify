# FIX-PLAN — AirPods Fluidify vs LibrePods (pinned 89356c08)

Data: 2026-11-20. Sintesi di A-settings-wire, B-session-background,
C-automations, D-ui, E-critic (+ follow-up M1 §5). Sola lettura su `app/` e
`engine/`: questo è un piano, nessuna correzione è stata applicata.

Abbreviazioni: NO = `app/src/main/java/dev/lelonio/square/…`;
UP = `build/librepods-upstream/android/app/src/main/java/me/kavishdevar/librepods/…`.

Principio guida (semantica LibrePods): il byte giusto c'è già quasi ovunque;
ciò che rompe l'affidabilità percepita è il recinto attorno — gate pre-invio,
macchina di conferma, ricevute causali, latch di sessione. Le correzioni sotto
eliminano/semplificano quel recinto verso il modello upstream: invio
incondizionato su socket connesso, stato ottimistico corretto dall'RX,
nessun concetto di conferma, servizio sempre vivo. Dove upstream ha un bug
documentato (lista duplicati `AACPManager:217-224`, refuso capability
`AirPodsSettingsScreen:446-447`, invii spontanei EQ/chime all'apertura,
batteria 0%), il piano dice esplicitamente di NON copiarlo.

## Ordine di esecuzione consigliato (impatto sull'affidabilità percepita)

| Priorità | FIX | Gruppo | Titolo breve |
|---|---|---|---|
| P0 | FIX-01 | 3 gate | Sganciare comandi e vita AACP dal proxy A2DP (`current()`) |
| P0 | FIX-02 | 2 stato | Eliminare il dedup anti-retry: ogni tap riscrive |
| P0 | FIX-03 | 3 gate | `canAttemptWrite`: da identità+SUBSCRIBED+modello a solo socket |
| P0 | FIX-04 | 2 stato | `SENT` senza scadenza + effective che afferma default/salvati |
| P0 | FIX-05 | 1 wire | Decode RX 0x09 tollerante (trim zeri, niente byte fissi) |
| P1 | FIX-06 | 1 wire | Gate di accettazione RX non-0x09 (batteria/CA/head) — M2 |
| P1 | FIX-07 | 3 gate | Prerequisito "valore già osservato" per 0x1A e STEM_CONFIG |
| P1 | FIX-08 | 4 sessione | Latch che bloccano la sessione (orfano, H9, link, cleanup, lease) |
| P1 | FIX-09 | 4 sessione | Fingerprint OTA, soppressione exit, verifica 30 s |
| P1 | FIX-10 | 4 sessione | Background: boot receiver, STICKY, trigger ridondanti |
| P1 | FIX-11 | 5 automazioni | `submit()` sincrono e veritiero (C-02) |
| P1 | FIX-12 | 5 automazioni | Pausa/ripresa: da ricevute 2 s + prova di rotta a bool al dispatch |
| P1 | FIX-13 | 5 automazioni | Ducking CA: ripristino volume sempre (C-03) |
| P1 | FIX-14 | 5 automazioni | Gesti testa: via contesto 250 ms, arming allo squillo (C-08/C-09) |
| P2 | FIX-15 | 5 automazioni | Ear: UNKNOWN, primo pacchetto, ripetuti, connect-on-insert |
| P2 | FIX-16 | 5 automazioni | Stelo custom 5/6/7 + STEM_CONFIG a connect + ordine ciclo unico |
| P2 | FIX-17 | 4 sessione | Sequenza handshake e fallimenti non fatali (H1/H6/H7, overflow) |
| P2 | FIX-18 | 6 UI | Gate UI incoerenti: POST_NOTIFICATIONS selettivo (D3) |
| P2 | FIX-19 | 6 UI | Fallimenti silenziosi e motivi mai mostrati (D2/D9) |
| P2 | FIX-20 | 6 UI | Ciclo/pagina lunga-pressione che mentono o sono inerti (D4/D5) |
| P2 | FIX-21 | 6 UI | Toggle coordinati, pila opt-in, locali non preconfigurabili |
| P3 | FIX-22 | 1 wire | Famiglie assenti: HEARING 0x2C/0x33/0x2F, EQ 0x53, canale ATT |
| P3 | FIX-23 | 1 wire | Smart Routing/TiPi: decisione O1/O2/O3, consigliata O2 RX-only |
| P3 | FIX-24 | 5 automazioni | Automazione carica: entrambi CHARGING → disconnectAudio (M3) |
| P3 | FIX-25 | 6 UI | Veridicità residua UI (rinomina, EQ, descrizioni, batteria…) |
| P3 | FIX-26 | 3 gate | Capability in scrittura → solo visibilità (MODE/ANC/one-bud) |
| P3 | FIX-27 | 5 automazioni | Numeri CA, volume manuale, disconnect-unworn (C-13/14/15) |

Le sezioni seguenti raggruppano per area (1 wire, 2 stato/conferma, 3 gate,
4 sessione/background, 5 automazioni, 6 UI); dentro ogni sezione i FIX sono
in ordine di priorità. Ogni FIX ha: sintomo, causa radice, correzione,
rischio, test di regressione, collaudo su hardware reale.

---

## 1. Divergenze wire

### FIX-05 (P0) — Decode RX 0x09 rigido: RX validi diventano `Unknown`

- **Sintomo:** un'impostazione non si conferma mai; la UI resta sul
  default/desired e il tap successivo può inviare il valore che il device
  ha già (nessun effetto percepibile). Caso documentato: ear `0x0A`.
- **Causa:** NO `airpods/protocol/AirPodsProperties.kt:40-49` esige 4 byte
  esatti, padding zero, `CHIME[1]==0x50`, `CALL[0]==0`, range stretto;
  `airpods/AacpCodec.kt:166-175` mappa ogni scarto a `Unknown` (mai
  OBSERVED; percorso speciale `AirPodsRepository.kt:576-582` per `0x0A`).
  UP `bluetooth/AACPManager.kt:1100-1120` prende 4 byte e trimma gli zeri
  finali. Correzione E/V4: upstream non "accetta tutto" — identifier ignoto
  tronca `receivePacket` (`:432`); la divergenza è sui payload malformati
  di identifier noti.
- **Correzione:** in `PropertySpec.decode` accettare i 4 byte grezzi e
  leggere solo i byte significativi per encoding (byte0 per SCALAR/BOOLEAN,
  byte0 per CHIME ignorando `0x50`, byte1 per CALL), con trim degli zeri
  come upstream; range fuori dominio → clamp/ignora-valore ma marca
  comunque OBSERVED-grezzo per diagnostica invece di `Unknown`. Mantenere
  il trattenimento diagnostico dei frame scartati. File:
  `AirPodsProperties.kt`, `AacpCodec.kt`, `AirPodsRepository.kt`
  (rimuovere il percorso speciale 0x0A quando il decode accetta).
- **Rischio:** medio-basso. Accettare padding sporco potrebbe mascherare un
  frame davvero corrotto; mitigato leggendo solo i byte definiti
  dall'encoding. Non copiare il truncation upstream su identifier ignoto.
- **Test:** aggiornare `airpods/protocol/ProtocolDecodingTest.kt`,
  `airpods/AacpCodecTest.kt`, `airpods/AirPodsUnknownEarEvidenceTest.kt`
  (oggi dichiara pacchetti "invented"): vettori con padding non zero,
  CHIME senza `0x50`, `0x0A` in codifica `0/1`, VOLUME_SWIPE_INTERVAL=0 →
  atteso OBSERVED, non Unknown. `PinnedWireVectorsTest.kt` invariato (TX).
- **Collaudo HW:** cattura RX grezza (strumentazione esistente
  `AirPodsRawRxCaptureTest`/diagnostica) su Pro 2/4/Max: per ogni proprietà
  cambiare valore dall'iPhone/Mac o da LibrePods e verificare che Fluidify
  la mostri OBSERVED entro un pacchetto; in particolare ear on/off.

### FIX-06 (P1) — Gate di accettazione RX non-0x09 mai auditati (E/M2)

- **Sintomo:** batteria ferma a `--`, ducking che non parte, gesti testa
  morti in silenzio se un firmware varia un byte di layout.
- **Causa:** NO `AacpCodec.kt:194-202` (batteria: `frame[6]==3`, chiavi
  {2,4,8}, status {1,2,5}), `:203-204` (CA: `frame[6]==2`, `frame[7]==0`,
  status {1,2,6,8,9}), `:186-192` (head: layout `00 00 10 00` + variante
  0x44/0x45 + `frame[11]==0`). UP: batteria grezza al callback
  (`AACPManager.kt:419-421`), CA `status=data[9]` (`data/Packets.kt:242-244`),
  head inoltrato se ≥70 byte (`AACPManager.kt:486-496`; il classificatore
  serve solo a sopprimere log, `AirPodsService.kt:2794`). Stem 0x19 invece
  in parità (`AACPManager.kt:251-265` vs `AacpCodec.kt:178-179`): non toccare.
- **Correzione:** allentare i tre gate alla sola condizione strutturale
  upstream (lunghezza minima + offset byte significativi); status CA ignoti
  → evento grezzo ignorato a valle (come oggi, stesso effetto netto);
  componenti batteria ignoti → scarta componente, non l'intero frame.
  File: `AacpCodec.kt` (+ consumatori batteria in repository/UI invariati).
- **Rischio:** basso. Un frame corto resta scartato; gli offset 51/53 head
  restano gli stessi.
- **Test:** nuovi vettori in `ProtocolDecodingTest.kt`/`AacpCodecTest.kt`:
  batteria con componente extra, CA con `frame[7]!=0`, head con byte di
  layout variato ma ≥70 byte e campioni 51/53 validi.
- **Collaudo HW:** sui tre modelli: batteria aggiornata aprendo la
  custodia, CA parlando (duck osservabile), head muovendo la testa in
  Diagnostica; confrontare con LibrePods sullo stesso paio.

### FIX-22 (P3) — Famiglie di impostazioni assenti: hearing, EQ 0x53, ATT

- **Sintomo:** apparecchio acustico non attivabile; niente loud-sound
  reduction, trasparenza personalizzata, regolazioni hearing, EQ
  telefono/media. Per l'utente senza vendor hook la superficie upstream è
  quasi pari (D13); con hook la mancanza è totale e silenziosa.
- **Causa:** nessuna spec `0x2C/0x33/0x2F` in NO
  `AirPodsProperties.kt:53-74` (UP scrive in coppia `[01,02]/[01,01]` +
  assist, `HearingAidScreen.kt:115-124,209-218`; HPS swipe
  `HearingAidAdjustmentsScreen.kt:172-177`); opcode `0x53` inesistente in
  NO `AacpCodec.kt:87-89,206` (UP `AACPManager.kt:1178-1206`, invio in gran
  parte incidentale da `AccessibilitySettingsScreen.kt:183-202`); nessun
  canale ATT da noi (UP `AirPodsViewModel.kt:643-663`, byte loud `01/00`
  in `AirPodsSettingsScreen.kt:479-484`), e nessun socket ATT
  (B/H5: UP solo con `vendor_id_hook`, `AirPodsService.kt:2655-2670`).
- **Correzione (in ordine):** (a) aggiungere spec+TX/RX per `0x2C+0x33` in
  coppia obbligatoria e `0x2F` — AACP puro, nessun privilegio; (b) valutare
  `0x53` solo se si vuole l'EQ telefono/media (non copiare l'invio neutro
  spontaneo all'apertura schermata); (c) ATT: solo con una decisione
  esplicita sul vendor hook — senza hook dichiarare la funzione
  "non disponibile su questo dispositivo" in UI invece di ometterla in
  silenzio. File: `AirPodsProperties.kt`, `AacpCodec.kt`,
  `AirPodsRepository.kt`, nuove pagine in `ui/airpods/`.
- **Rischio:** medio (a: scrittura in coppia su funzione sanitaria —
  rispettare byte0=enrolled letto da RX, mai inventarlo); alto (c:
  superficie privilegiata).
- **Test:** vettori TX coppia on/off in `PinnedWireVectorsTest.kt` dai
  byte upstream citati; decode RX enrolled/active in
  `ProtocolDecodingTest.kt`.
- **Collaudo HW:** solo su modello con HEARING_AID e confronto incrociato
  con LibrePods (stesso paio, toggle da entrambe le app, stato letto
  dall'altra). ATT: solo su device con hook, se si sceglie (c).

### FIX-23 (P3) — Smart Routing / TiPi assente: decidere O1/O2/O3 (E/M1+§5)

- **Sintomo:** solo multi-device: un Mac/iPad "ruba" l'audio e da noi
  sparisce senza spiegazione; ritorno solo manuale. Solo-Android: impatto
  nullo (UP `AACPManager.kt:865-870,908-912` richiede un secondo host).
- **Causa:** NO `AacpCodec.kt:87-89,165-206` nessun branch per RX
  `0x0E/0x2E/0x11` né TX `0x10` (UP TX `:802,858,901,958,1002,1036`, RX
  `:507-559`; reazione sempre attiva in `AirPodsService.kt:953-1019`).
  Prerequisiti parità piena assenti e non ottenibili da app normale:
  `vendor_id_hook` (`AirPodsService.kt:2475-2478`), MAC proprio
  privilegiato/root (`:389-420`), spec `OWNS_CONNECTION 0x06` (A §24).
  Il rifiuto della claim di ownership è già deliberato da noi
  (`AirPodsRepository.kt:277,315,471`).
- **Correzione consigliata — O2 RX-only:** decodificare `0x0E/0x2E/0x11`
  in evento "audio spostato su altro dispositivo" → pausa + avviso UI
  (island/notifica), nessun TX, nessun hijack. Richiede policy retention
  per MAC/nomi terzi nei payload (mai loggarli in chiaro come UP
  `:546-549`; oggi da noi mai registrati, `AacpCodec.kt:100-112`).
  Alternative: O1 ignorare consapevolmente (documentarlo in UI/help);
  O3 parità piena — sconsigliata: bloccata dai prerequisiti, hijack
  aggressivo (`localscore 100`, `AACPManager.kt:883-892`), e upstream la
  vende opt-in default-off (`AppSettingsScreen.kt:293-342`).
  File (O2): `AacpCodec.kt`, `AirPodsRepository.kt`, evento verso
  runtime/UI.
- **Rischio:** O2 basso-medio (parsing testuale fragile upstream
  `:535-545` da non copiare letteralmente; tipo device solo indicativo).
- **Test:** vettori RX sintetici `0x11 SetOwnershipToFalse/ShowNearbyUI`
  e `0x0E` in `ProtocolDecodingTest.kt`; test che nessun MAC finisca nei
  log/diagnostica esportata.
- **Collaudo HW:** richiede secondo host Apple: play su Android → play su
  Mac/iPad → atteso avviso + pausa; senza secondo host la correzione è
  non collaudabile (resta coperta dai soli vettori).

---

## 2. Macchina di stato / conferma da rimuovere o ridurre

### FIX-02 (P0) — Dedup anti-retry: il secondo tap è un successo senza pacchetto

- **Sintomo:** l'utente ritocca lo stesso controllo ("non ha preso, riprovo")
  e non succede nulla, per sempre, finché la sessione non cade.
- **Causa:** NO `AirPodsRepository.kt:368-370`: desired uguale in
  QUEUED/WRITING/SENT → `return true` senza wire. UP
  `AirPodsViewModel.kt:370-378` + `data/ControlCommandRepository.kt:35-40`
  reinvia a ogni tap (es. maschera modi `AirPodsViewModel.kt:756-766`).
- **Correzione:** eliminare il ramo dedup; ogni `setProperty` accoda e
  scrive (la coalescenza A→B→C in coda, `AirPodsRepository.kt:374,390-461`,
  può restare: collassa solo valori diversi non ancora scritti). Stesso
  trattamento per EQ e rename, che condividono la macchina. File:
  `AirPodsRepository.kt`.
- **Rischio:** basso. Più pacchetti identici sul wire (ciò che fa
  LibrePods); nessun effetto firmware noto da audit.
- **Test:** `airpods/AirPodsRepositoryTest.kt`: due `setProperty` identici
  consecutivi (anche dopo SENT senza RX) → due write fisiche; aggiornare
  eventuali test che asseriscono il dedup.
- **Collaudo HW:** impostare un valore, forzare un invio a vuoto (bud in
  custodia chiusa/A2DP altrove), ritoccare lo stesso valore: deve partire
  un nuovo pacchetto (log TX) e il cambio deve applicarsi.

### FIX-04 (P0) — `SENT` eterno ed `effective` che afferma valori mai letti

- **Sintomo:** interruttori già ON (Ear/AutoConn/AllowOff) prima di
  qualsiasi lettura; valori "attivi" che il device non ha mai confermato;
  switch che dopo minuti tornano indietro da soli senza messaggio.
- **Causa:** NO `airpods/protocol/AirPodsPropertyState.kt:54-62`
  (`propertyEffective`: DESIRED SENT/UNCERTAIN > RX > salvato > default di
  tabella `:34-52`) + nessun timeout su SENT (commento `:7`);
  `NOT_CONFIRMED` dichiarato e mai assegnato
  (`AirPodsRepository.kt:25`, `AirPodsProperties.kt:96,107-120`);
  UI scarta `EffectiveSource` (`ui/airpods/AirPodsControlPieces.kt:69-70`);
  scelte restaurate degradate a UNCERTAIN ma rese come fatti
  (`AirPodsPropertyState.kt:20-26`). UP: mappa ottimistica corretta da RX,
  assente=spento (`AirPodsViewModel.kt:370-421`).
- **Correzione (ridurre, non replicare l'ottimismo cieco):**
  1. introdurre la scadenza già promessa dai testi ("entro 5 s"): SENT
     senza RX entro 5 s → `NOT_CONFIRMED` realmente assegnato, effective
     ricade sull'ultimo OBSERVED (o su "sconosciuto");
  2. prima del primo RX della sessione mostrare "—"/sconosciuto invece dei
     default di tabella per i booleani (tenere i default numerici solo
     come posizione iniziale slider, marcata non letta);
  3. propagare `EffectiveSource` in UI: stato *awaiting* visibile (esiste
     già `OptionCell(awaiting)`/`ChoiceMark` in
     `ui/airpods/AirPodsPresentation.kt:231-301`, mai usati; D7) e revert
     su CONTRADICTED con messaggio (collega FIX-19).
  File: `AirPodsPropertyState.kt`, `AirPodsProperties.kt`,
  `AirPodsRepository.kt` (timer/riconciliazione `:593-627`),
  `AirPodsControlPieces.kt`, `AirPodsMainPage.kt`, `AirPodsUiModel.kt`.
- **Rischio:** medio — tocca il modello di stato condiviso da tutte le
  superfici (capsula, tile, wear). Mitigazione: mantenere
  `propertyEffective` come API ma cambiarne la semantica nei tre punti.
- **Test:** `ui/airpods/AirPodsListeningModeEffectiveTest.kt`,
  `AirPodsUiModelTest.kt`, `AirPodsRepositoryTest.kt`: SENT+silenzio 5 s →
  NOT_CONFIRMED e fallback; cold start senza RX → sconosciuto, non ON;
  RX contraddittorio → revert + outcome visibile. Aggiornare
  `AirPodsI03OutcomeUiTest.kt`/`AirPodsI03PresentationTest.kt` se
  asseriscono i default come fatti.
- **Collaudo HW:** dissociare/riassociare e aprire subito la pagina prima
  del burst RX: nessuno switch deve affermare ON; cambiare un valore da
  LibrePods a Fluidify aperta: la UI deve seguire l'RX, non il desired.

---

## 3. Gate pre-invio

### FIX-01 (P0) — `current()`: comandi e vita della sessione legati al proxy A2DP

- **Sintomo:** "a volte va, a volte no" senza pattern visibile; sessione
  che si smonta quando l'audio si sposta su un altro output pur con
  canale di controllo sano. È, per B, la causa più diretta del sintomo.
- **Causa:** NO `airpods/runtime/android/AirPodsAndroidRuntime.kt:951-962`
  (`current()` rivalutata ≥3× per comando: `:826,946-950,1531-1532,1293`)
  richiede `facts.connected()` = proxy A2DP che elenca il device in
  quell'istante (`AndroidPlatform.kt:107-112`; proxy in bind perso →
  UNRESOLVED → false, `:76-82`); `refresh()` con ticket non-current smonta
  l'intera sessione AACP (`:1067-1077`). UP: unico gate socket
  (`AACPManager.kt:1162-1167`); A2DP e AACP indipendenti dopo la connect
  (`AirPodsService.kt:2956-3088`).
- **Correzione:** per il percorso comandi sostituire `current()` completa
  con un controllo minimo stile upstream: generazione/trasporto attivi e
  socket connesso. L'A2DP resti un *trigger* di avvio/ripresa e un fatto
  per le automazioni media, mai una condizione di write né di teardown
  AACP: a `refresh()`, A2DP giù non deve più smontare la sessione (solo
  eventi socket/ACL di disconnessione reale). File:
  `AirPodsAndroidRuntime.kt` (`writable/current/GuardedTransport`),
  `AndroidPlatform.kt` invariato come sorgente di fatti.
- **Rischio:** medio-alto — `current()` è anche fence di sicurezza
  (consenso, soppressione, coppia selezionata). Tenere in `writable()` i
  soli check di consenso/soppressione/coppia (stabili, non time-varying),
  togliere bonded/connected/productConformance istantanei dal path di
  write. Non indebolire lo stop esplicito utente.
- **Test:** `runtime/android/I4RuntimeAuthorizationTest.kt`,
  `AirPodsPlatformGateTest.kt`, `AirPodsFunctionalAdmissionTest.kt`,
  `runtime/I5ForegroundFenceTest.kt`: flicker A2DP (connected→UNRESOLVED→
  connected) durante sessione SUBSCRIBED → comandi continuano a partire e
  la generazione non cade; consenso revocato → comandi ancora bloccati.
- **Collaudo HW:** con sessione attiva, spostare l'output audio su
  altoparlante (A2DP giù) e cambiare modalità ANC da Fluidify: deve
  applicarsi; riassegnare l'audio ai bud senza riaprire l'app.

### FIX-03 (P0) — `canAttemptWrite`: identità riconosciuta + SUBSCRIBED o niente

- **Sintomo:** subito dopo la connessione (o per tutta la sessione, se
  INFORMATION non parsa o modello fuori tabella) ogni impostazione è
  muta, pur con socket perfetto; poi "a un certo punto" funziona.
- **Causa:** NO `AirPodsRepository.kt:62-84,364-366` (+ ricontrollo in
  worker `:403-409,421-424`): `identityReceived` di generazione corrente,
  modello riconosciuto (manufacturer + modelNumber in tabella locale,
  `AacpCodec.kt:16-18`, parse rigido `:214-235`), stage SUBSCRIBED,
  capability, range. UP `ControlCommandRepository.kt:35-40` pass-through;
  modello ignoto salta solo i metadata (`AirPodsService.kt:1053-1067`);
  istanza mancante → finta A3049 (`AirPodsViewModel.kt:594-605`).
- **Correzione:** ridurre `canAttemptWrite` a: spec esistente + valore in
  range + trasporto scrivibile. Identità/capability restino criteri di
  *visibilità UI* (FIX-26), non di rifiuto: modello sconosciuto = invia
  comunque le proprietà senza capability richiesta, come upstream.
  Mantenere la validazione di range (upstream tronca: non copiarlo).
  File: `AirPodsRepository.kt`, `AirPodsProperties.kt` (candidate resta
  per la UI).
- **Rischio:** medio — si può scrivere verso un modello che ignora
  l'identifier: esito identico a LibrePods (firmware ignora). Il rischio
  vero è perdere la protezione "non scrivere a coppia sbagliata": resta
  garantita da generazione/trasporto (FIX-01), non dall'identità.
- **Test:** `AirPodsRepositoryTest.kt`, `AacpObservedInformationCoreTest.kt`:
  write prima di INFORMATION e con modelNumber ignoto → pacchetto parte;
  valore fuori range → ancora rifiutato localmente.
- **Collaudo HW:** appena connessi, prima che la pagina mostri
  modello/seriali, cambiare modo: deve applicarsi subito; se disponibile
  un modello non in tabella (clone/firmware nuovo), verificare che i
  comandi base partano.

### FIX-07 (P1) — Prerequisito "già osservato" per maschera modi e STEM_CONFIG

- **Sintomo:** ciclo lunga-pressione e azione lunga personalizzata morti
  per l'intera sessione se il burst iniziale non include `0x1A`/`0x39`.
- **Causa:** NO `AirPodsRepository.kt:73-76` (0x1A esige
  `propertyObserved` corrente a SUBSCRIBED, `:54-61`) e
  `AirPodsAndroidRuntime.kt:826,880-907` (STEM vietata da `setProperty`
  pubblica; `setLongPress` rifiuta senza maschera osservata `:888-889`;
  eventi ignorati senza `:910-912`). UP parte da default `0`
  (`AirPodsViewModel.kt:757`) e invia la maschera STEM completa dai soli
  dati host a ogni setup (`AirPodsService.kt:836-854`).
- **Correzione:** senza RX, partire da maschera nota salvata o da `0`
  come upstream e scrivere comunque; per STEM preservare i bit bassi solo
  se osservati, altrimenti scrivere la maschera calcolata dalla config
  host (collega FIX-16: riaffermarla a connect). File:
  `AirPodsRepository.kt`, `AirPodsAndroidRuntime.kt`.
- **Rischio:** basso-medio — scrivere una maschera 0x1A costruita su base
  ignota può azzerare modi impostati da iOS; mitigato usando l'ultima
  osservazione salvata come base e il toggle a bit singolo dell'UI.
- **Test:** `AirPodsRepositoryTest.kt`, `runtime/android/I5LongPressRuntimeTest.kt`,
  `ui/airpods/AirPodsC3GestureChoicesTest.kt`: toggle maschera senza RX
  di sessione → write con base salvata/0; setLongPress senza RX 0x39 → write.
- **Collaudo HW:** connessione fresca, andare subito in Lunga pressione e
  cambiare ciclo/azione prima di qualsiasi altra interazione: deve
  applicarsi e il long-press fisico deve seguirla.

### FIX-26 (P3) — Capability usate per rifiutare la scrittura, non solo per mostrarla

- **Sintomo:** su alcuni modelli voci assenti/bloccate che LibrePods
  invia (Adattivo su modelli LISTENING_MODE senza ADAPTIVE_AUDIO; one-bud
  ANC senza LISTENING_MODE).
- **Causa:** NO `AirPodsProperties.kt:28-29,58,66` + `AirPodsRepository.kt:69-75`
  + `AacpCodec.kt:19-20` (MODE=4 e bit maschera 0x08 subordinati ad
  ADAPTIVE_AUDIO; interlock ALLOW_OFF su *effective* `:70-72`). UP usa le
  capability solo per la visibilità e offre sempre Adattivo con
  LISTENING_MODE (`NoiseControlSettings.kt:95-127`); ALLOW_OFF nasconde
  solo UI (`PressAndHoldSettingsScreen.kt:148`).
  Attenzione: per AUTO_ANC il gate upstream è un **refuso**
  (`ADAPTIVE_VOLUME` invece di `ADAPTIVE_AUDIO`,
  `AirPodsSettingsScreen.kt:446-447`): tenerlo presente, non copiarlo —
  il nostro gate ADAPTIVE_AUDIO è quello giusto per la *visibilità*.
- **Correzione:** capability → solo visibilità (con FIX-03 la scrittura
  non le consulta più); interlock ALLOW_OFF: mantenere l'occultamento UI
  dello slot Off ma rimuovere il rifiuto in `canAttemptWrite` (un ciclo
  hardware può legittimamente includere Off). File:
  `AirPodsRepository.kt`, `AirPodsProperties.kt`, `AirPodsUiModel.kt`.
- **Rischio:** basso.
- **Test:** `AirPodsRepositoryTest.kt`, `ui/airpods/AirPodsUiModelTest.kt`:
  MODE=4 su modello senza ADAPTIVE_AUDIO → write parte, UI non lo offre;
  maschera con bit Off e ALLOW_OFF effettivo=2 → write parte.
- **Collaudo HW:** su AirPods 4 (no ANC) verificare che strength resti
  nascosta (come da noi oggi) e su Pro 1 che Adattivo, se offerto via
  ciclo, non rompa nulla (firmware ignora o applica).

---

## 4. Sessione / background

### FIX-08 (P1) — Latch di sessione senza via d'uscita (upstream non li ha)

- **Sintomo:** dopo un errore transitorio i controlli restano morti finché
  non si esce/rientra, si fa ricadere il BT o si riavvia il processo.
- **Causa (cinque latch, B §4):** (a) ticket orfano: servizio `stopSelf`
  prima di `attachedOwner` (`AirPodsComponents.kt:30,52`,
  `AirPodsAndroidRuntime.kt:1485`) → gate `active` senza socket,
  ALREADY_ACTIVE perenne (`AirPodsStartGate.kt:202`); (b) stage fermo:
  bootstrap abortito senza eccezione non riprova mai, `bootstrapGeneration`
  consumata (`AirPodsRepository.kt:283,296-298` — H9); (c) link consumato
  prima dell'esito start (`AirPodsStartGate.kt:234,241-245`); (d)
  CLEANUP_PENDING che blocca anche `explicitConsent` e rinuncia a 2 s
  (`:130-135,205,268`, `AirPodsAndroidRuntime.kt:1422-1435`); (e) lease
  mai rilasciata se `close()` lancia o la read non si sblocca
  (`AirPodsTransportOwnership.kt:62-64,78-80`); (f) retry vietato nella
  stessa apertura che ha visto l'errore
  (`AirPodsAndroidRuntime.kt:977,1451-1472`). UP: due `var` statici
  sovrascrivibili (`BluetoothConnectionManager.kt:27-30`), retry libero
  (`AirPodsService.kt:2636,3179-3191`).
- **Correzione:** rendere ogni latch *scadibile e ritentabile*: (a) se il
  servizio muore senza attach, il gate deve ricadere a inattivo (watchdog
  su `attachedOwner==null` o notifica incondizionata in `onDestroy`);
  (b) bootstrap fallito senza eccezione → ERROR esplicito e teardown,
  mai silenzio; (c) consumare l'ordinale link solo a start riuscito;
  (d) CLEANUP_PENDING con timeout → forza COMPLETE e logga, non blocca
  il consenso esplicito; (e) lease: `finally` che marca closed anche su
  eccezione + retry worker per read bloccate; (f) pulsante/azione
  "Riprova connessione" nella stessa schermata, equivalente di
  `reconnectFromSavedMac`. File: `AirPodsStartGate.kt`,
  `AirPodsAndroidRuntime.kt`, `AirPodsComponents.kt`,
  `AirPodsTransportOwnership.kt`, `AirPodsRepository.kt`.
- **Rischio:** alto per area (ciclo di vita) — procedere latch per latch,
  ognuno col suo test; non riscrivere il gate in un colpo solo.
- **Test:** `runtime/lifecycle/AirPodsStartGateTest.kt`,
  `AirPodsStartGateDeferredCleanupTest.kt`,
  `AirPodsI01ControlRecoveryGateTest.kt`,
  `airpods/AirPodsTransportOwnershipTest.kt`,
  `runtime/android/AirPodsCleanupRecoveryTest.kt`,
  `AirPodsAutomaticReconnectTest.kt`: un caso di sblocco per latch
  (orfano→nuovo attempt ammesso; bootstrap abortito→ERROR; start
  fallito→link ritentabile; cleanup stalled→consenso ammesso; close che
  lancia→lease libera).
- **Collaudo HW:** forzare errori transitori (BT off/on rapido, bud in
  custodia durante connect, kill del processo da Recents) e verificare il
  recupero dalla stessa schermata senza riavvio app.

### FIX-09 (P1) — Fingerprint OTA, soppressione per exit "sporca", verifica 30 s

- **Sintomo:** dopo un aggiornamento di sistema (o un kill anomalo) i
  controlli tornano muti finché l'utente non rifà un flusso di Setup che
  non si aspetta.
- **Causa:** NO `AirPodsStartGate.kt:197` (PRODUCT esige
  `conformanceBuild == Build.FINGERPRINT` corrente; attenuante E/V5:
  ri-verifica esplicita esiste, `:188-190`, ma è manuale); soppressione
  durevole da marker di uscita non pulito/history non affidabile
  (`:180,224,350-371`, `AndroidPlatform.kt:21-27`); `refresh()` con
  permesso transitoriamente negativo → soppressione durevole
  (`AirPodsAndroidRuntime.kt:1059-1062`); sessione di verifica che a 30 s
  sopprime USER_STOP e richiede un secondo consenso (`:1268-1271,1474-1476`).
  UP: nessun concetto equivalente.
- **Correzione:** (a) a fingerprint cambiata, avviare automaticamente la
  ri-verifica silente alla prima connessione utile (o degradare a
  richiesta una-tantum in pagina, non a blocco totale); (b) soppressione
  exit: solo su evidenza forte (crash ripetuti nel percorso AirPods),
  mai su history non interrogabile o singolo kill LMK; (c) permesso
  mancante a `refresh()` → stato "in attesa permesso", non soppressione
  durevole; (d) a verifica riuscita promuovere subito a PRODUCT senza
  secondo consenso. File: `AirPodsStartGate.kt`,
  `AirPodsAndroidRuntime.kt`.
- **Rischio:** medio — queste difese nascono da requisiti di conformance;
  mantenere la registrazione/diagnostica, togliere solo la *permanenza*
  del blocco senza azione utente possibile in pagina.
- **Test:** `AirPodsStartGateTest.kt`, `AirPodsQualificationGateTest.kt`,
  `runtime/android/AirPodsPlatformGateTest.kt`: fingerprint diversa +
  connessione buona → recupero senza Setup manuale; kill LMK singolo →
  nessuna soppressione; permesso revocato→riconcesso → ripresa.
- **Collaudo HW:** simulare OTA cambiando fingerprint attesa in build di
  test (o dopo un vero update): i controlli devono tornare alla prima
  connessione senza passare da Setup.

### FIX-10 (P1) — Background: niente boot, servizio non-sticky, trigger singoli

- **Sintomo:** dopo riavvio telefono o kill di sistema gli AirPods si
  connettono in audio ma Fluidify non li controlla finché non si apre l'app.
- **Causa:** NO nessun `RECEIVE_BOOT_COMPLETED`/boot receiver
  (`AndroidManifest.xml:76-79`), receiver BT dinamico che muore col
  processo (`AirPodsAndroidRuntime.kt:429-445`), `START_NOT_STICKY`
  ovunque (`AirPodsComponents.kt:26-48`), avvio background solo via CDM
  completa (`AirPodsAndroidRuntime.kt:985-993,1020`). UP: `BootReceiver`
  incondizionato (`BootReceiver.kt:30-44`), `START_STICKY`
  (`AirPodsService.kt:2445`), trigger ridondanti ACL/scan bonded/BLE
  (`:683,769,258,797-799`), mai `stopSelf`.
- **Correzione (compatibile con FGS Android 12+):** aggiungere boot
  receiver + `MY_PACKAGE_REPLACED` che avvia il FGS connectedDevice solo
  se esiste una coppia abilitata (a differenza di upstream,
  incondizionato); `START_STICKY` per la sessione prodotto; mantenere il
  percorso CDM come trigger aggiuntivo, non unico; a socket chiuso
  restare vivi e ritentare al prossimo ACL (collega FIX-08f). File:
  `AndroidManifest.xml`, nuovo receiver, `AirPodsComponents.kt`,
  `AirPodsAndroidRuntime.kt`.
- **Rischio:** medio — consumo batteria/notifica permanente: mitigare
  avviando il FGS solo con coppia abilitata e fermandolo su stop
  esplicito/forget (upstream non si ferma mai: non copiarlo).
- **Test:** `runtime/android/AirPodsComponentsTest.kt`,
  `AirPodsApplicationStartupTest.kt` (in `runtime/`): intent BOOT con
  coppia abilitata → start richiesto; senza coppia → no-op; kill+restart
  sticky → sessione ricostruita.
- **Collaudo HW:** riavvio telefono → indossare i bud senza aprire
  Fluidify → ANC/ear devono funzionare; swipe-recents → riconnessione
  bud → controlli vivi. Verificare assenza di FGS orfano dopo Forget.

### FIX-17 (P2) — Sequenza handshake e semantica dei fallimenti

- **Sintomo:** connessione "riuscita" ma comandi morti; una write andata
  male durante il bootstrap uccide tutto; sotto carico head-tracking la
  sessione muore.
- **Causa:** NO byte identici ma sequenza diversa: `0x29` subito come 4°
  pacchetto senza ritardi (`AirPodsRepository.kt:290-291` vs UP seconda
  raffica a ~800 ms, `AirPodsService.kt:2744-2752`); proximity keys mai
  richieste (`AirPodsRepository.kt:276-278` vs UP `:2743,2754,2760`);
  nessun ciclo start/stop head-tracking (UP `:2755,2761`); write di
  bootstrap fallita → ERROR→teardown (`AirPodsRepository.kt:308,332` vs
  UP logga e continua, `AACPManager.kt:1172-1175`); overflow coda 1024 →
  generazione ERROR (`AirPodsRepository.kt:129,546-547`); read loop
  prima del bootstrap e recheck post-connect che può annullare un
  successo fisico (`:262`, `AirPodsAndroidRuntime.kt:1510-1513`).
- **Correzione:** allineare le raffiche a upstream (immediata senza
  `0x29`; seconda a passi di 200 ms con `0x29`; terza a 5 s), rendere
  ogni singola write di handshake non fatale (fallimento loggato,
  SUBSCRIBED raggiunto comunque se subscribe è partita), overflow →
  drop dei soli eventi head-tracking in eccesso invece di ERROR.
  Proximity keys: decisione separata (BLE/FindMy) — se non si
  implementano, documentare che l'assenza è scelta e non un bug; l'effetto
  firmware è NV senza cattura. Ciclo head-tracking: solo se serve a
  sbloccare i gesti (verificare con FIX-14 su HW prima di aggiungerlo).
  File: `AirPodsRepository.kt`, `AirPodsAndroidRuntime.kt`.
- **Rischio:** medio — le tempistiche upstream sono empiriche; cambiare
  ordine/ritardi va collaudato per modello.
- **Test:** `airpods/AirPodsBootstrapTest.kt`,
  `runtime/android/AirPodsControllerBootstrapTest.kt`: sequenza e
  tempistiche delle tre raffiche; write #2 che lancia → sessione viva;
  valanga eventi head → drop selettivo, generazione viva.
- **Collaudo HW:** 20 cicli custodia→orecchio per modello misurando
  tempo-a-SUBSCRIBED e successo primo comando; confronto con LibrePods
  sullo stesso paio.

---

## 5. Automazioni

### FIX-11 (P1) — `submit()` dichiara successo prima dell'esecuzione (C-02)

- **Sintomo:** automazione "accettata" (e UI/telemetria contente) ma
  nessun effetto: la musica non si ferma, il volume non si muove.
- **Causa:** NO `airpods/runtime/media/MediaRuntimeCore.kt:208-232`:
  `queue.post { … return@post … }` e `return true` incondizionato a
  `:231`; i rifiuti (epoca/lease/target/`foreignDuckActive`/rotta)
  avvengono dopo, in silenzio. Il coordinator registra pending/rampa sul
  `true`. UP dispatch sincrono `@Synchronized`
  (`utils/MediaController.kt:258-311`).
- **Correzione:** eseguire i controlli di ammissione *prima* di
  rispondere (stesso thread del trigger o callback d'esito obbligatoria
  consumata dal coordinator): `submit` torna accolto solo se l'effetto è
  stato dispatchato o verrà dispatchato con certezza nella post
  (pre-validata). Ogni rifiuto a valle deve annullare il pending
  registrato. File: `MediaRuntimeCore.kt`,
  `AirPodsAutomationCoordinator.kt`, `AndroidMediaEnvironment.kt`.
- **Rischio:** medio — possibile lavoro sincrono aggiunto sul percorso
  trigger; mantenerlo a soli check in memoria, dispatch invariato.
- **Test:** `runtime/media/MediaRuntimeCoreTest.kt`,
  `runtime/automation/AirPodsAutomationCoordinatorTest.kt`: submit verso
  target stantia/lease scaduta → falso e nessun pending; submit valido →
  effetto osservato una sola volta.
- **Collaudo HW:** con app esterna in play, togliere un bud: pausa entro
  ~1 s nel 100% di 20 prove (oggi intermittente); log senza pending orfani.

### FIX-12 (P1) — Pausa/ripresa: ricevute causali 2 s e prova di rotta (C-01/C-06)

- **Sintomo:** togliere un bud a volte non pausa; rimetterlo a volte non
  riprende. È il cuore automazioni dell'"esito incoerente".
- **Causa:** NO `AirPodsAutomationCoordinator.kt:159-334` (ownedPause
  solo con ricevuta `observed==effect.id` entro 2 s; invalidazione totale
  a ogni snapshot non eligibile `:140-148`; doppio consenso
  origine/motivo `:318-334`) + prova di rotta esterna a congiunzione
  (`AndroidMediaEnvironment.kt:77-161`,
  `ExternalBestEffortProvider.kt:49-97`: NLS + unico A2DP + unica uscita
  BT + streaming/storia). UP: `iPausedTheMedia` posto al dispatch,
  ripresa col solo bool, tasto media globale
  (`MediaController.kt:258-311`).
- **Correzione:** adottare il modello upstream come percorso primario:
  al dispatch della pausa marca "pausa nostra" (per target); al
  reinserimento invia PLAY se il flag è nostro e la sessione è la
  stessa — senza ricevuta causale. Le ricevute restino *telemetria* (e
  revoca su evidenza forte: play manuale utente sulla stessa sessione),
  non condizione. Per le app esterne: tasto globale verso la sessione
  attiva di sistema come upstream; la prova di rotta diventi criterio
  per le sole funzioni che la richiedono davvero (disconnect), non per
  play/pause. Mantenere distinto il percorso locale Fluidify (bridge
  `playback/PlaybackAirPodsMediaBridge.kt:24`, da auditare: vedi residui)
  dove le ricevute sono affidabili per costruzione. File:
  `AirPodsAutomationCoordinator.kt`, `AutomationModel.kt`,
  `AndroidMediaEnvironment.kt`, `ExternalBestEffortProvider.kt`,
  `MediaRuntimeCore.kt`.
- **Rischio:** medio-alto comportamentale: PLAY può partire su una
  sessione che l'utente aveva fermato a mano *dopo* la nostra pausa se
  la revoca non intercetta il play manuale — upstream ha lo stesso
  profilo (mitigato da `userPlayedTheMedia`); importare anche quella
  guardia. Tasto globale senza prova di rotta può colpire una sessione
  non-AirPods: accettato upstream, da dichiarare in UI.
- **Test:** `AirPodsAutomationCoordinatorTest.kt`,
  `runtime/media/C6ResumeCorrelationTest.kt`,
  `ResumeAuthorityMatrixTest.kt`, `ExternalBestEffortProviderTest.kt`:
  riscrivere i casi "ricevuta assente/tardia → ripresa comunque";
  "play manuale dopo nostra pausa → niente doppio play";
  due sessioni attive → comportamento definito e testato (sessione attiva
  di sistema), non silenzio.
- **Collaudo HW:** matrice app (Fluidify, Spotify/YouTube) ×
  (togli SX, togli DX, togli entrambi, in custodia) × 10 ripetizioni:
  pausa e ripresa deterministiche; confronto affiancato con LibrePods.

### FIX-13 (P1) — Ducking CA: volume che resta abbassato (C-03)

- **Sintomo:** dopo una conversazione il volume resta basso; a volte il
  duck non parte affatto; a volte salta anche la ripresa ear.
- **Causa:** NO `AirPodsAutomationCoordinator.kt:181-233,348-412`: ogni
  passo di rampa attende ricevuta esatta entro 2 s; su timeout/interferenza
  `abandonEffects` azzera duck **senza ripristino** e cancella anche
  ownedPause; `MediaCandidate.foreignDuckActive` default `true`
  (`MediaRuntimeCore.kt:24,163`). UP rampa incondizionata e *ripristino
  sempre* (`MediaController.kt:313-362`).
- **Correzione:** rampa a passi temporizzati senza ricevuta per-passo
  (verifica finale best-effort); il livello originario va salvato e
  *sempre* ripristinato a fine CA — anche su abbandono/timeout/cambio
  target (ripristino = ramo di default, non di successo); separare le
  sorti di duck e ownedPause (un fallimento volume non cancella la
  pausa); default `foreignDuckActive=false` salvo attestazione del
  bridge. File: `AirPodsAutomationCoordinator.kt`, `MediaRuntimeCore.kt`,
  `AndroidAirPodsMediaRuntime.kt`.
- **Rischio:** basso-medio — ripristinare sempre può sovrascrivere un
  cambio volume manuale durante la conversazione (comportamento
  upstream, noto e accettabile; annotarlo in UI).
- **Test:** `AirPodsAutomationCoordinatorTest.kt`,
  `runtime/media/ConversationRecoveryMediaRuntimeTest.kt`,
  `I02TransportVolumeCoordinatorTest.kt` (in `runtime/automation/`):
  timeout a metà rampa → volume finale = originario; abbandono → pausa
  ear ancora ripresa-capace.
- **Collaudo HW:** parlare con CA on (musica in play), interrompere a
  metà (bud in custodia, cambio app): il volume deve tornare al valore
  pre-conversazione in tutti i rami.

### FIX-14 (P1) — Gesti testa: contesto 250 ms e arming sbagliato (C-08/C-09)

- **Sintomo:** annuire/scuotere per rispondere a volte funziona, a volte
  il cenno è "non visto"; nessun feedback per riprovare.
- **Causa:** algoritmo in parità (600/150/300/400/0.7, offset 51/53) ma
  recinto NO: cache contesto 250 ms con età da *prima* della lettura
  (`HeadGestureContextCache.kt:8-24`), gesto consumato e detector
  resettato se il contesto è scaduto
  (`AirPodsAndroidRuntime.kt:253-265`), reset a ogni cambio chiave
  (revision media a *ogni* callback, `:185-187,240-259`; `AndroidAirPodsMediaRuntime.kt:44-51`),
  arming da settings/visibilità invece che allo squillo (`:390-394`),
  rifiuti "chiamata ambigua" e one-shot senza retry
  (`platform/OrdinaryCallOperations.kt:28-70`); nessun feedback sonoro
  (UP `GestureDetector.kt:120-123,159-178`). UP: detector avviato a
  RINGING, ogni campione conta (`AirPodsService.kt:601-628,2107-2123`).
- **Correzione:** allineare l'arming: tracking+detector partono a
  chiamata in arrivo (se toggle on) e si fermano a IDLE; durante lo
  squillo niente cache di contesto a scadenza — chiave = (generazione,
  epoca chiamata) rivalutata solo al dispatch; niente reset per revision
  media irrilevanti; gesto riconosciuto fuori contesto = *non* consumato;
  one-shot con retry su rifiuto piattaforma fino a IDLE; ripristinare un
  feedback (tono di conferma) come upstream. Le azioni media nod/shake
  restino opt-in nostre (FIX-21/D14) ma fuori dal percorso chiamata.
  File: `AirPodsAndroidRuntime.kt`, `HeadGestureContextCache.kt` (da
  eliminare o ridurre a cache senza scadenza nel percorso squillo),
  `PinnedHeadGestureDetector.kt` (feedback hook),
  `OrdinaryCallOperations.kt`.
- **Rischio:** medio — tracking acceso solo a squillo riduce il consumo
  rispetto a oggi; falsi positivi chiamata mitigati da epoca+dispatch
  check. Non toccare soglie/algoritmo (parità a singola fonte per il
  corpo UP: vedi residui).
- **Test:** `runtime/android/HeadGestureContextCacheTest.kt` (da
  riscrivere sulla nuova semantica), `AirPodsHeadGestureContinuityTest.kt`,
  `runtime/media/AirPodsGestureContextInvalidationTest.kt`,
  `platform/OrdinaryCallOperationsTest.kt`: campioni sintetici con gap
  >250 ms e revision media intermedie → cenno comunque riconosciuto e
  dispatchato una volta; rifiuto piattaforma → secondo cenno dispatcha.
- **Collaudo HW:** 20 chiamate in arrivo (auto-chiamata da secondo
  telefono): nod=rispondi, shake=rifiuta, tasso di successo e latenza
  confrontati con LibrePods; cenno "presto" subito dopo lo squillo incluso.

### FIX-15 (P2) — Ear: UNKNOWN, primo pacchetto, ripetuti, connect (C-04/C-05)

- **Sintomo:** ripresa persa dopo un glitch; musica che suona con bud in
  custodia; connessione con bud già indossati che non riprende.
- **Causa:** NO byte `>2`→UNKNOWN che invalida resume e divora anche la
  transizione successiva (`AirPodsProperties.kt:86`,
  `AirPodsAutomationCoordinator.kt:244-272`); primo pacchetto solo
  baseline (`:244-247`); NONE ripetuto ignorato (`:248`) e nessun
  connect A2DP all'inserimento. UP: non-zero=fuori, default freddo
  `[01,01]`=NONE (`data/Packets.kt:94`, `AirPodsService.kt:1235-1247`),
  pausa forzata a ogni NONE (`:1274-1275`), `connectAudio`+PLAY al
  connect (`:1267-1287,1335-1342`).
- **Correzione:** trattare ogni non-zero come fuori (custodia `02`
  inclusa, già in parità); UNKNOWN solo per frame malformati a monte
  (FIX-06) e senza invalidare baseline/resume; baseline iniziale = NONE
  come upstream; a NONE ripetuto con musica PLAYING ri-valutare la pausa
  (o almeno all'avvio musica con bud fuori); all'inserimento da NONE
  richiedere connect A2DP e agganciare la PLAY al suo esito — con
  FIX-01 il controllo AACP non dipende più dall'A2DP, quindi il connect
  torna a essere un effetto, non un prerequisito. File:
  `AirPodsAutomationCoordinator.kt`, `AirPodsProperties.kt`
  (`EarSample.normalized:85-91`), `AirPodsAndroidRuntime.kt`.
- **Rischio:** basso-medio — ri-pausa su ripetuti può fermare una
  riproduzione volutamente avviata a bud fuori: è il comportamento
  upstream; limitarla al caso toggle ear-pause on (già così).
- **Test:** `airpods/protocol/AirPodsDecodedEarAutomationTest.kt`,
  `AirPodsEarProtocolTest.kt`, `AirPodsAutomationCoordinatorTest.kt`:
  sequenza [BOTH, glitch>2, NONE] → una sola transizione valida persa=0;
  primo pacchetto BOTH a freddo → trattato da inserimento.
- **Collaudo HW:** avviare musica da altoparlante con bud in custodia
  (ear-pause on): deve fermarsi al pacchetto ear successivo; estrarre e
  indossare: connect+play senza aprire l'app.

### FIX-16 (P2) — Stelo custom inesistenti, STEM a connect, ordine ciclo (C-11/C-12)

- **Sintomo:** personalizzazioni single/double/triple senza effetto;
  long-press assistente che non parte se la maschera non è stata vista;
  ciclo modi con ordine diverso tra stelo e tile.
- **Causa:** NO unico handler `press==8`
  (`AirPodsAndroidRuntime.kt:909-912`), runtime media che scarta gli
  eventi stelo (`AirPodsMediaPort.kt:53-54`,
  `AndroidAirPodsMediaRuntime.kt:79-80`), maschera scrivibile solo bit 8
  e solo se osservata (`:888-907`, FIX-07), due ordini ciclo
  (`:933-941` OFF-ANC-TRASP-ADATT vs `ListeningModeCycle.kt:12-17`
  OFF-TRASP-ADATT-ANC). UP: 4 tipi × 2 bud × 5 azioni, maschera
  ricalcolata e inviata a ogni setup/connect
  (`data/StemAction.kt:23-38`, `AirPodsService.kt:821-854,1188-1231`).
  Quirk upstream da non copiare: il ramo PLAY_PAUSE host è
  irraggiungibile (`:1201-1207`).
- **Correzione:** decidere il perimetro onesto e implementarlo tutto:
  config per tipo/bud, calcolo maschera completa (1/2/4/8) dalla config,
  invio STEM_CONFIG a connect e a ogni cambio config, handler host per
  5/6/7/8 (PLAY_PAUSE/NEXT/PREV via tasto globale, ASSISTANT, CYCLE) con
  **un unico** ordine ciclo condiviso stelo/tile/quick. Se il perimetro
  scelto resta solo long-press, l'UI non deve offrire altro (FIX-25).
  Gate assistente: ridurre a API33+toggle come upstream, via
  `noCallOrMix`/visibilità/CDM dal dispatch (`:920-931`). File:
  `AirPodsAndroidRuntime.kt`, `ListeningModeCycle.kt`,
  `AndroidAirPodsMediaRuntime.kt`, `AirPodsMediaPort.kt`,
  storage config, `ui/airpods/AirPodsGesturePages.kt`.
- **Rischio:** medio — intercettare single/double toglie i default
  firmware: se l'app è morta, il gesto è muto (upstream identico);
  riaffermare la maschera a connect e azzerarla a Forget/stop.
- **Test:** `runtime/ListeningModeCycleTest.kt` (unico ordine),
  `runtime/android/I5LongPressRuntimeTest.kt`,
  `ui/airpods/host/AirPodsI5StemHostTest.kt`,
  `runtime/AirPodsMediaEventFilterTest.kt`: config→maschera per le 16
  combinazioni; evento 5/6/7 con azione custom → effetto; evento con bit
  non custom → nessun effetto host.
- **Collaudo HW:** per ogni gesto customizzato verificare l'azione con
  app in foreground e in background; kill app → i gesti custom sono muti
  ma i default non intercettati devono restare firmware.

### FIX-24 (P3) — Automazione carica assente (E/M3)

- **Sintomo:** bud riposti in custodia in carica trattengono/rilasciano
  la rotta A2DP diversamente da LibrePods.
- **Causa:** UP `AirPodsService.kt:883-887`: entrambi CHARGING →
  `disconnectAudio`, altrimenti `connectAudio` a ogni pacchetto
  batteria. Nessun equivalente NO (solo percorso unworn
  `AirPodsAndroidRuntime.kt:312`).
- **Correzione:** valutare l'adozione dietro lo stesso toggle della
  famiglia disconnect (non sempre-on come upstream, per non sorprendere
  chi usa un solo bud mentre l'altro carica — la condizione "entrambi"
  lo copre già, ma dichiararlo in UI). File: `AirPodsAndroidRuntime.kt`,
  handler batteria in `AirPodsRepository.kt`.
- **Rischio:** basso se dietro toggle default off.
- **Test:** unit sul reducer batteria→intento audio (entrambi CHARGING,
  uno solo, nessuno).
- **Collaudo HW:** riporre entrambi i bud in custodia in carica con
  musica attiva: rotta rilasciata; estrarne uno: rotta ripresa.

### FIX-27 (P3) — Numeri CA, volume manuale, disconnect-unworn (C-13/14/15)

- **Sintomo:** percentuale duck clampata in silenzio; volume da
  tile/background muto; disconnect che non scatta se la transizione non
  è stata vista in sessione.
- **Causa:** clamp `10..85` e default 40 NO (`AutomationModel.kt:36`,
  `AirPodsRuntimeData.kt:41-45`) vs UP libero default 43 e troncamento
  (`MediaController.kt:320-326` — incluso il suo `*` vs `/` incoerente
  `:77` vs `:86`, da non copiare); `manualVolume` solo se UI visible
  (`MediaRuntimeCore.kt:249-265`) e revoca totale su intento manuale
  (`:280-284`); disconnect solo su transizione fresca ≤5 s + riverifiche
  (`AirPodsAndroidRuntime.kt:309-335,1215-1224`) vs UP a ogni NONE
  (`AirPodsService.kt:1274-1278`).
- **Correzione:** allineare la formula percentuale (relativo =
  `initial*pct/100`, assoluto di conseguenza) mantenendo il clamp come
  *limite UI dichiarato*, non silenzioso; consentire `manualVolume`
  anche da background con lease valida e far convivere l'intento manuale
  col duck (nuovo baseline, non revoca distruttiva — con FIX-13);
  disconnect: valutare anche su stato NONE corrente all'avvio sessione,
  non solo su transizione osservata. File: come sopra.
- **Rischio:** basso.
- **Test:** `I02TransportVolumeCoordinatorTest.kt`,
  `runtime/media/I5PhoneStreamVolumeTest.kt`: tabella percentuali→step
  uguale a upstream per pct 10..85; volume manuale durante duck →
  baseline aggiornata e ripristino al nuovo valore.
- **Collaudo HW:** confronto numerico del livello duckato Fluidify vs
  LibrePods a parità di volume iniziale e percentuale.

---

## 6. UI

### FIX-18 (P2) — Gate incoerenti nella stessa schermata: notifiche (D3)

- **Sintomo:** senza permesso notifiche alcuni controlli funzionano e
  altri sono grigi/rifiutati, senza spiegazione.
- **Causa (meccanismo esatto, E/V7):** `actionKey(Write)=null`
  (`ui/airpods/host/AirPodsHostPolicy.kt:66`) → dispatch salta `allowed()`
  (`AirPodsHost.kt:311`): le Write dipendono solo da `writeAllowed`
  (`AirPodsUiModel.kt:120-122`, niente notifiche); strength/gesti/tracking
  invece passano da `liveReason` che include `POST_NOTIFICATIONS`
  (`AirPodsHost.kt:134-141,270-277`). UP: nessun gate notifiche sui
  controlli device (`AirPodsSettingsScreen.kt:264`).
- **Correzione:** togliere le notifiche da `liveReason` per tutte le
  azioni *dispositivo/host* (restino requisito solo per superfici che
  notificano davvero, es. island batteria); uniformare: ogni controllo
  usa la stessa coppia (writeAllowed per AACP, availability per il
  resto) e la stessa fonte di verità in UI e dispatch. File:
  `AirPodsHost.kt`, `AirPodsHostPolicy.kt`, `AirPodsUiModel.kt`,
  `AirPodsMainPage.kt`.
- **Rischio:** basso.
- **Test:** `ui/airpods/host/AirPodsHostPolicyTest.kt`,
  `AirPodsI4AvailabilityTest.kt`: matrice (notifiche on/off × ogni
  azione) → disponibilità identica per i controlli device.
- **Collaudo HW:** revocare POST_NOTIFICATIONS: strength, gesti stelo e
  tracking devono comportarsi come gli switch vicini.

### FIX-19 (P2) — Fallimenti silenziosi fuori dalla pagina Main (D2/D7/D9)

- **Sintomo:** tocco senza effetto e senza messaggio su Chiamate/Controlli/
  EQ/Rinomina/Stelo; switch grigi senza motivo.
- **Causa:** unico feedback `MainSettingsFailure`
  (`ui/airpods/AirPodsMainPage.kt:243-262`, solo Main e solo FAILED);
  rifiuti host mappati solo in `state.diagnostics`
  (`AirPodsHost.kt:294,302-311,400`) visibili in Diagnostica;
  `availability property.*` calcolata e mai mostrata
  (`AirPodsHost.kt:270-277` vs `AirPodsMainPage.kt:264-275`);
  CONTRADICTED/UNCERTAIN ignorati da ogni superficie (con FIX-04 vanno
  mostrati). UP non ha uno strato di ammissione che rifiuti il tocco.
- **Correzione:** portare in pagina, ovunque ci sia un controllo: (a)
  esito dell'ultimo dispatch (spinner awaiting → conferma o messaggio
  breve tipo l'esemplare `airpods_c4_partial_save`), (b) motivo di
  disabilitazione come sottotitolo (pattern già presente su Setup/locali:
  `AirPodsSetupPages.kt:153-168`, `AirPodsControlPieces.kt:137-141` —
  estenderlo a switch principali, modalità, strength, chiamate, EQ,
  rinomina, lunga-pressione). Con FIX-01/03 i rifiuti veri diventano
  rari; questo FIX li rende comunque visibili. File: pagine in
  `ui/airpods/`, `AirPodsHost.kt` (esporre outcome per-pagina, non solo
  diagnostics).
- **Rischio:** basso (solo presentazione + plumbing outcome).
- **Test:** `ui/airpods/AirPodsI03OutcomeUiTest.kt`,
  `host/AirPodsI03HostOutcomeTest.kt`, `AirPodsC4MainPageTest.kt`,
  `AirPodsC5PagesTest.kt`: dispatch rifiutato in ogni pagina → messaggio
  composto in pagina; controllo disabilitato → motivo non nullo reso.
- **Collaudo HW:** forzare i rifiuti residui (BT off a pagina aperta,
  sessione caduta) e toccare ogni controllo: sempre un riscontro visibile.

### FIX-20 (P2) — Ciclo e pagina lunga-pressione che mentono o tacciono (D4/D5)

- **Sintomo:** primo tocco sul ciclo accende lo switch ma il device non
  cambia; pagina SX/DX inerte senza motivo finché non arriva `0x39`.
- **Causa:** stato locale `incomplete` senza dispatch né avviso per
  maschere <2 bit (`AirPodsGestureChoices.kt:43-47,65-77`);
  `stemPresentation` blocca senza comporre il motivo esistente
  (`AirPodsStemPresentation.kt:23-28,68-79`,
  `AirPodsGesturePages.kt:60-84,163-173`); unico tentativo = resubscribe
  invisibile (`AirPodsGesturePages.kt:67-76`). UP invia a ogni tocco e
  registra sempre la scelta locale
  (`AirPodsViewModel.kt:739-766`, `PressAndHoldSettingsScreen.kt:96-119`).
- **Correzione:** con FIX-07 (niente prerequisito osservato) la pagina
  torna viva: registrare subito la scelta locale e inviare; per il ciclo
  <2 bit scegliere una semantica onesta: o inviare anche maschere a 1 bit
  come upstream (da validare su HW: accettazione firmware NV) o mantenere
  il vincolo ≥2 mostrando "scegline almeno due" e *non* accendere lo
  switch finché la maschera non è inviabile. Comporre sempre
  `StemStatusSummary`/motivo. File: `AirPodsGestureChoices.kt`,
  `AirPodsGesturePages.kt`, `AirPodsStemPresentation.kt`.
- **Rischio:** basso; l'unica insidia è la maschera a 1 bit (tenere il
  vincolo finché il collaudo non la legittima).
- **Test:** `ui/airpods/AirPodsC3GestureChoicesTest.kt`,
  `AirPodsC3GesturePagesTest.kt`: da maschera 0/1-bit il primo tocco non
  produce mai "acceso in UI ∧ niente sul wire"; pagina senza RX 0x39 →
  scelte abilitate con motivo/azione chiara.
- **Collaudo HW:** azzerare il ciclo da iOS/LibrePods, poi ricostruirlo da
  Fluidify un modo alla volta verificando il long-press fisico a ogni passo.

### FIX-21 (P2) — Toggle coordinati, pila opt-in, locali bloccate (D6/C-07/D11/D14)

- **Sintomo:** "CON ON ma non succede nulla": lo switch dispositivo non
  dice che pilota anche pref locali; le pagine Main/Automazioni si
  contraddicono; le impostazioni locali non si preconfigurano da
  disconnessi; "gesti testa ON" con azioni NONE.
- **Causa:** coordinamento nascosto e asimmetrico
  (`AirPodsMainToggle.kt:22-60`: Ear scrive device+`earPause/Resume`;
  Conversation a OFF non spegne `duck`); lettura Main solo dal numero
  device (`AirPodsMainPage.kt:265`); default tutti `false` e congiunzione
  master+otherApps+background+NLS (`AirPodsRuntimeData.kt:33-43`,
  `MediaRuntimeCore.kt:89-141`) vs UP toggle singoli default ON
  (`AirPodsService.kt:166-172,444-465`); accensione locali subordinata a
  `configured` (`AirPodsHost.kt:124-133,234-258`); nod/shake default NONE
  (`AirPodsRuntimeData.kt:60-61`) e semantica chiamata non rappresentata
  (D14).
- **Correzione:** (a) dichiarare nel sottotitolo cosa coordina ogni
  switch (o scollegare: switch device = solo device, automazioni = solo
  pagina Automazioni, con la Main che riflette entrambi gli stati in due
  righe distinte); Conversation OFF deve spegnere anche duck/pause
  locali o dirlo; (b) la pagina Automazioni deve rendere visibile la
  congiunzione richiesta (checklist master/altre-app/background/NLS con
  deep-link ai permessi) — un toggle ON con prerequisito mancante mostra
  il prerequisito, non il silenzio; (c) consentire la pre-configurazione
  locale da disconnessi (effetto alla prossima connessione, come
  SharedPreferences upstream); (d) gesti testa: etichettare la semantica
  chiamata ("annuisci per rispondere…") come upstream, azioni media come
  sezione separata opzionale, test del gesto in pagina (non solo
  Diagnostica). File: `AirPodsMainToggle.kt`, `AirPodsMainPage.kt`,
  `AirPodsHost.kt`, `AirPodsGesturePages.kt`, stringhe EN/IT.
- **Rischio:** medio (cambio di contratto percepito dei due switch
  coordinati — migrare le pref esistenti senza invertirne il significato).
- **Test:** `ui/airpods/AirPodsC4CoordinationTest.kt`,
  `AirPodsUiModelTest.kt`, `host/AirPodsHostFacadeTest.kt`: OFF
  Conversation → duck locale coerente; toggle locale da disconnesso →
  salvato e applicato a connect; master off → sottopagine mostra il perché.
- **Collaudo HW:** da installazione pulita, seguire solo ciò che la UI
  mostra per ottenere pausa-ear su Spotify in background: deve bastare
  senza conoscenze esterne; confrontare i passi con LibrePods.

### FIX-25 (P3) — Veridicità e navigabilità residue (D8/D10/D12–D23)

Raggruppa correzioni piccole e indipendenti; farle in un unico passaggio
UI dopo FIX-18/19/21. Per ognuna: causa → correzione.

- **Gruppi che spariscono (D8):** `DeviceGroups` non composto se manca un
  anello (`AirPodsMainPage.kt:55-80`, `AirPodsUiModel.kt:74-84`) →
  comporre sempre i gruppi disabilitati col motivo specifico (con
  FIX-01/03 gli anelli A2DP/SUBSCRIBED escono comunque da `controlsReady`).
- **Descrizioni assenti (D10/D23):** solo titoli, anche tecnici EN
  (`AirPodsMainPage.kt:266-274`, `AirPodsUiStrings.kt:14-22`) → importare
  i sottotitoli upstream equivalenti (AllowOff con avviso suoni forti,
  PPE 82 dBA, estremi strength…) in `airpods_strings.xml` EN+IT
  (parità 277/277 da mantenere).
- **Rinomina (D12):** draft dato per inviato prima dell'esito e perso in
  silenzio (`AirPodsDeviceHeader.kt:198-213`) → `submitted()` solo a
  dispatch accolta; errore in pagina; contatore byte 255; titolo segnato
  "in attesa" finché INFORMATION non conferma (FIX-04).
- **EQ (D15):** slider attivi a EQ spento, reset piatto muto
  (`AirPodsEqualizerControlsPage.kt:31-72`) → disabilitare le bande a
  spento (come upstream) e il reset a curva piatta; **non** copiare
  l'invio spontaneo upstream all'apertura (già corretto da noi).
- **Chiamate/Mic in principale (D16):** riga Calls senza valori
  (`AirPodsMainPage.kt:140-143`) → mostrare mute/end e mic correnti +
  riga informativa Answer, come upstream.
- **Disconnetti (D17):** solo in Setup, in principale c'è Forget
  (`AirPodsMainPage.kt:104-111`) → aggiungere Disconnect (chiusura
  sessione AACP, semantica LibrePods) in principale, Forget resta in fondo.
- **Riga steli (D18):** override `usesHardwareStemCycle` contro la scelta
  salvata (`AirPodsStemPresentation.kt:49-66`, `AirPodsHost.kt:280-283`)
  → mostrare scelta salvata e, se diversa dall'hardware osservato, badge
  "da applicare"; dichiarare che l'azione custom dipende dall'app viva.
- **Slot Off (D19):** guidato da effective/intento (`AirPodsUiModel.kt:88-92`)
  → con FIX-04/26 segue l'osservato; intento in attesa marcato awaiting.
- **Tile/Information (D20):** etichette che promettono azioni inesistenti
  (`AirPodsSetupPages.kt:92-99`) → rinominare in informativo o
  implementare; candidati Setup sempre toccabili ma respinti → FIX-19.
- **Batteria (D21):** gate `homeVisible` più larghi del dato e
  capsula≠pagina (`AirPodsUiModel.kt:225-241`) → mostrare i campioni
  AACP freschi ovunque; stessa regola stale (etichettata) su capsula e
  pagina; mantenere `--` invece dello 0% upstream (nostro miglioramento).
- **Tap (D22):** ingresso condizionato alla capsula → garantire una via
  ≤2 tap stabile per modo/CA (capsula sempre presente a coppia abilitata
  o azione rapida in Home); non peggiorare strength inline (già migliore).

- **Rischio:** basso singolarmente; attenzione solo a stringhe EN/IT in
  parità e a non reintrodurre invii spontanei in composizione.
- **Test:** `ui/airpods/AirPodsC5EqualizerTest.kt`,
  `AirPodsHeaderPresentationTest.kt`, `AirPodsCapsuleBatteryTest.kt`,
  `AirPodsHomeEntryTest.kt`, `AirPodsFinalProductLayoutTest.kt`,
  `AirPodsPresentationTest.kt`: un'asserzione per voce sopra
  (draft preservata su rifiuto; reset piatto disabilitato; batteria
  capsula==pagina a parità di campioni…).
- **Collaudo HW:** giro completo pagine con TalkBack e font 1.5×;
  rinomina con nome lungo/UTF-8; verifica che nessuna apertura pagina
  invii pacchetti (log TX vuoto a sola navigazione).

---

## Cosa NON divergemmo (già allineato — da non "correggere")

Verificato da A–E sui corpi; nessuna azione richiesta, solo regressioni
da proteggere con i test esistenti.

- **Byte TX delle 20 proprietà `0x09` implementate, EQ `0x63` e rename
  `0x1A`**: nessuna divergenza quando il pacchetto parte — inclusi
  `CHIME [v,0x50]`, `CALL [0,2|3]`, maschere modi/stem (bit `1 shl (wire-1)`),
  booleani `1/2` e singola inversione `100-x` di AUTO_ANC_STRENGTH
  (A §Sintesi, §§1-21; struttura pacchetto `AacpCodec.kt:61-64` =
  `AACPManager.kt:288-303`). Proteggere con `PinnedWireVectorsTest.kt`.
- **Handshake**: stesse 5 firme riflessive socket, PSM 0x1001/UUID AACP,
  byte handshake/featureFlags D7/notifications FF×4/`0x29` identici; tipo
  FGS connectedDevice in entrambi i manifest (B §5).
- **Trigger automazioni**: byte ear 6/7 con custodia `02`=fuori, swap
  LEFT↔RIGHT inerte, baseline aggiornata da disabilitato; status CA
  `1/2` attivo e `6/8/9` inattivo; algoritmo gesti testa (soglie e offset
  51/53 — lato UP a singola fonte, vedi residui); dominio duck
  `STREAM_MUSIC`; primo CA attivo senza baseline (C, parità verificate).
- **Accettazione stem `0x19`** (press 5..8, bud 1..2): parità, col nostro
  `Unknown` più sicuro del throw upstream (E/M2).
- **Framing RX multi-frame per SDU** nostro (`AacpCodec.kt:128-211`,
  buffer ≥MTU in `AirPodsSocket.kt:68-72`) è *migliore* del mono-pacchetto
  upstream da 1024 byte (`AirPodsService.kt:2772-2792`): non allineare
  all'indietro (E/M4).
- **Nessun claim OWNS_CONNECTION in handshake** su entrambi i lati
  (nostro deliberato: `AirPodsRepository.kt:277`; upstream solo in takeover).
- **Bug upstream non replicati** (D §5, A): niente invii spontanei EQ/chime
  all'apertura schermata; niente batteria 0% per componenti mai sentiti
  (`--` onesto); selezioni accessibilità non congelate da `remember`;
  gate AUTO_ANC su ADAPTIVE_AUDIO corretto (refuso upstream su
  ADAPTIVE_VOLUME); capability per-modello combaciano 1:1 tra le tabelle.
- **Stringhe**: parità completa EN/IT 277/277 e riferimenti esistenti
  (D §6, conteggio a singola fonte — vedi residui); tipografia accessibile
  nostra in più (`AirPodsReadableToggle`) senza equivalenti upstream.

## Rischi residui non verificabili senza hardware

Da E §4 + unresolved A–D. Nessuna lettura ulteriore li chiude: servono
device, catture RX o una decisione.

1. **Forma reale dei frame respinti dai gate RX** (0x09 malformati — in
   particolare il payload `0x0A` sul device utente, oggi solo pacchetti
   "invented" nei test — e frame non-0x09 di FIX-06): servono catture RX
   grezze dal dispositivo per sapere cosa il firmware manda davvero e
   tarare FIX-05/06 senza indovinare.
2. **Frequenza reale dei pacchetti ear/head** e quindi quanto mordono in
   pratica finestre e latch (250 ms/1 s/2 s, arrivo RX `0x39`/`0x1A`,
   rifiuti host): i vicoli ciechi sono provati da codice, la loro
   frequenza no.
3. **Accettazione firmware**: maschere ciclo a 1 bit; scritture senza
   ownership in scenario multi-device (A §24 non lo afferma); effetto
   dell'assenza proximity-keys e dell'anticipo `0x29` (FIX-17).
4. **Bridge locale mai auditato**: qualità delle ricevute di
   `playback/PlaybackAirPodsMediaBridge.kt` (439 righe, esiste — E/M5
   corregge C) e valore reale di `foreignDuckActive`: condiziona
   FIX-12/13 sul percorso locale.
5. **Serializzazione runtime mai auditata**
   (`AirPodsRuntimeCodec`/`AirPodsRuntimeStore`, più
   `AirPodsPairValueStore` che alimenta i ripristini UNCERTAIN): rischio
   "stato stale restaurato come fatto" né confermato né escluso —
   da auditare prima o insieme a FIX-04.
6. **Affermazioni a singola fonte**: conteggi B (~24 gate comando / ~22
   avvio), parità algoritmo gesture lato UP `GestureDetector.kt`,
   conteggi stringhe D (277/277): plausibili e coerenti coi corpi
   riaperti dal critico, non ri-contati; la parità byte delle singole 20
   proprietà è ri-verificata a campione, non ri-eseguita per intero (E §3).
7. **Decisione di prodotto Smart Routing (FIX-23)**: chiude solo con la
   scelta O1/O2/O3, che dipende da un dato non in repo (quanti utenti
   Fluidify hanno un secondo host Apple).
8. **Non letti per intero / fuori perimetro**: JNI `enableExemptions()`
   (solo "false è fatale"), `AirPodsServiceOwnerRegistry` e
   `CompanionObservationPolicy` ai soli call-site, funnel onboarding e
   superficie QS Tile fuori da `ui/airpods`, comportamento TalkBack/font
   estremi su device, ordine ciclo del receiver upstream `SET_ANC_MODE`,
   semantica `connectAudio` NONE→BOTH con A2DP già connesso.
9. **Verso in etichetta dello slider AUTO_ANC** ("più ANC" a quale
   estremo): mapping numerico identico, icone upstream private-use non
   interpretabili da sorgente.
