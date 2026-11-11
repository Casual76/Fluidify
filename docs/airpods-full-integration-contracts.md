# Contratti operativi AirPods — piano F1

Normativi per F1, primo giro correttivo della nuova pianificazione. Non sono codice
implementato o prove hardware. Completano i requisiti esistenti e prevalgono sulle
proposte meno specifiche delle discovery; F0 e i report storici restano immutati.

C6 è una decisione utente successiva a F1: per le sole sessioni esterne autorizza
la policy best effort sotto descritta. Non modifica retroattivamente il congelato
F1, non allenta le ricevute locali e non elimina il gate fisico.

## C1 — Formato head tracking

`host.head_gestures` include **Pacchetti head tracking alternativi** in Impostazioni
complete→Gesti testa→Compatibilità. Preferenza locale per coppia verificata,
persistita, default true come il getter upstream; distinta da abilitazione gesti
e da capability ricevute. La chiave `use_alternate_head_tracking_packets` ha mapping
semantico esplicito a `host.head_gestures` nel catalogo.

| Preferenza | Start | Stop |
|---|---|---|
| false | createStartHeadTrackingPacket | createStopHeadTrackingPacket |
| true | createAlternateStartHeadTrackingPacket | createAlternateStopHeadTrackingPacket |

Fonti pinned: `AACPManager.kt:661–779`, `AirPodsService.kt:3145–3172` e toggle attivo
`AppSettingsScreen.kt:344–350`. Fixture esatte per ciascun payload. Il tracker
conserva activePacketFormat all'avvio: cambio preferenza durante tracking comporta
stop col formato attivo, completamento write/invalidazione tracker e start col
nuovo formato solo se ancora richiesto. Errore stop: chiudere sessione e invalidare
tracking, senza sovrapporre formati. Nessun takeover o alternanza a timer.
Test: entrambi i formati, persistenza per coppia, startA→cambioB→stopA→startB,
errore stop, disconnect e callback vecchie. Tracking fisico senza takeover resta NV.

## C2 — Capacità dichiarate, annunciate e osservate

Non è obbligatorio un annuncio dedicato non identificato dal protocollo pinned:
onCapabilitiesReceived nel servizio è TODO e non definisce un decoder necessario.

| Evidenza | Origine | Uso |
|---|---|---|
| modelDeclared | Tabella statica pinned applicata al modello da INFORMATION RX valido | Candidati UI/probe; dato derivato statico, non capability RX |
| deviceAnnounced | Eventuale messaggio con layout/semantica verificati | Opzionale, null se assente; non requisito implicito né falso universale |
| propertyObserved | RX pertinente validato della singola proprietà/evento nella sessione corrente | Stato e supporto osservati per quella proprietà; non per tutte le altre |

Per proprietà distinguere candidata, valore unknown, osservata, pending/non
confermata, esclusa da dipendenza e indisponibile per causa concreta di piattaforma/
permesso. Cache e default non sono propertyObserved corrente. Timeout non significa
incompatibile. `canAttemptWrite`: identità riconosciuta corrente, proprietà applicabile
secondo catalogo/modello, percorso/permessi/sessione validi, range valido e nessun
pending della stessa proprietà. Non richiede deviceAnnounced.

Se manca RX, la UI può offrire una scelta esplicita candidata con “Da verificare”
e nessun valore selezionato inventato; il comando resta non confermato fino a RX.
Unknown e modelli esplicitamente privi della funzione non usano fallback Pro3.
Gate setup: identità INFORMATION valida, canale/bootstrap e dati di base osservati,
mapping modello registrato con provenienza; nessun annuncio indefinito richiesto.
Gate famiglia: RX/readback previsto per ogni proprietà e comportamento fisico del
relativo scenario; per automazioni locali, preferenza salvata+evento device+effetto
host osservati. La matrice finale riporta separatamente le tre evidenze.
Test: modello noto senza annuncio e mode RX; candidata senza RX→write→RX match;
timeout senza incompatibilità; unknown senza fallback; vecchia generazione; RX di
una proprietà incapace di certificare altre proprietà. Non chiamare RX la tabella.

## C3 — Presenza companion, manifest e versioni

Prima di CDM verificare FEATURE_COMPANION_DEVICE_SETUP e dichiarare uses-feature
`android.software.companion_device_setup` required=false. API31+: dichiarare
REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE e
REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND. CompanionDeviceService:
exported=true, permission BIND_COMPANION_DEVICE_SERVICE e intent filter
`android.companion.CompanionDeviceService`, abilitato con risorsa di versione solo
da API31. AirPodsService non esportato. Il companion è un trigger, senza socket.

| API | Associazione/osservazione | Callback e rilascio |
|---|---|---|
| 26–30 | Setup CDM se feature presente; nessuna API presenza31 invocata | Percorso legacy/manuale e start FGS solo quando consentito; nessuna callback inesistente promessa |
| 31–32 | Consenso, address/bond scelti verificati, startObservingDevicePresence(address) | onDeviceAppeared/Disappeared(address), stopObservingDevicePresence(address) |
| 33–35 | getMyAssociations e associationId verificato; osservazione address associato | Callback AssociationInfo/address deduplicate; stop della stessa osservazione |
| 36+ | ObservingDevicePresenceRequest con associationId verificato, startObservingDevicePresence(request) | onDevicePresenceEvent e stopObservingDevicePresence(request) |

Sequenza: feature→consenso→rilettura associazione Fluidify della coppia bonded→
registrazione osservazione effettiva→callback→gate C4→connessione Bluetooth reale→
unico owner. Associazione non equivale a osservazione; non salvare “registrato” prima
dell'esito API. Presenza BLE non equivale a connessione audio/AACP. Classic genera
callback su connect/disconnect, ma il profilo effettivo viene comunque verificato.
Stop/disattivazione disregistrano quando possibile. Dimentica/unpair disregistrano
e disassociano solo Fluidify; non eliminare bond o associazioni di altre app.
L'autorità di rimozione usa l'handle pubblico dell'associazione propria (ID e
indirizzo dove disponibili, indirizzo proprio nelle API legacy), ricontrollato
prima dell'operazione. Metadati mutabili non identificano un'incarnazione e una
loro variazione non dimostra assenza. Un nuovo setup esplicito della stessa coppia
revoca durevolmente la vecchia richiesta di rimozione, senza presentarla come
disassociazione riuscita. Errori di query conservano il recupero; associazioni con
ID/indirizzo/package differenti e nuovi intenti di setup restano protetti. Le API
ordinarie non forniscono una cancellazione condizionata atomica: non attribuire
una garanzia contro sostituzioni OS indistinguibili in tutti i campi pubblici
nell'intervallo finale fra controllo e chiamata.
Revoca/disassociazione invalidano callback precedenti. Feature assente, associazione
errata, permission/security error e FGS negato hanno cause distinte: un errore di
manifest non viene classificato come telefono incompatibile.

Fonti primarie verificate 2026-11-08: [CDM](https://developer.android.com/reference/android/companion/CompanionDeviceManager#startObservingDevicePresence(java.lang.String))
e [CompanionDeviceService](https://developer.android.com/reference/android/companion/CompanionDeviceService).
Test con adattatore fake per ogni ramo: registrazione davvero emessa, deny/revoke,
wrong device/association, feature assente, duplicati, start/stop bilanciati e zero
acquisizioni senza C4. Comportamento OEM/presenza reale resta gate fisico.

## C4 — Stop di sistema prima degli ingressi automatici

Un solo AirPodsStartGate serializzato precede qualsiasi acquisizione: Application,
receiver, companion, restore, Tile listening e NLS callback non aprono socket.
Binding automatico o apertura generale dell'app non sono Riattiva AirPods.
Scelta prodotto CORRECTIVE9: l'apertura ordinaria di MainActivity (lancio, nuovo
intento di apertura o ritorno dopo Home/Recenti) ripristina una sessione PRODUCT
solo con opt-in e consenso preesistenti, setup valido per la build, conformance,
permessi, bond e connessione effettiva. Non scrive consenso e non cancella suppress:
storico ExitInfo/marker, Stop/force-stop, causa indeterminata, cleanup pendente e
owner unico restano governati dallo stesso C4. Il solo foreground, la ricreazione
per configurazione, binding, permessi/settings/associazione/export di ritorno e gli
altri ingressi osservativi non acquisiscono. Il profilo Bluetooth irrisolto concede
una sola attesa limitata, legata all'apertura, alla coppia, al consenso e all'epoch
foreground correnti; nessun retry o trasferimento al callback passivo. La nuova
sessione riparte senza pause, duck, comandi pending o ownership media precedenti.
Il gate precede anche il nuovo avvio di AirPodsService/FGS, non soltanto connect;
il componente companion può essere legato dal sistema ma non riabilita da solo
il servizio fermato. L'abilitazione iniziale esplicita vale come Riattiva per
riconoscere eventi storici già noti prima del primo consenso.
Persistire enabled, suppressUntilExplicitResume e motivo, consenso/generazione,
marker sessione/process epoch, chiavi uscita trattate e ultimo evento riconosciuto
esplicitamente. Scrivere marker prima dell'acquisizione; cleanup normale lo conclude.
Non trattare marker come prova di processo vivo o lock di un'altra sessione.

API30+: getHistoricalProcessExitReasons del proprio package/processo prima di
ammettere ingressi automatici. Nuovo REASON_USER_REQUESTED→persistire suppress e
consumo dell'evento atomicamente. Chiave composta timestamp/pid/processName/reason,
non solo PID/clock. Storico assente o indeterminato con marker di processo precedente
non concluso→suppress conservativo. API26–29 senza ExitInfo: stesso fallback dal
marker; nessuna morte non osservabile diventa consenso implicito al riavvio.

| Evento | Policy |
|---|---|
| Arresta pannello/notifica | suppress prima del cleanup; disregistrare osservazione quando possibile |
| Disattiva | enabled=false e suppress=true; stop owner/osservazione |
| Task Manager | Nessun callback atteso; exit USER_REQUESTED al ritorno blocca companion/receiver/restore |
| Force-stop | Non aggirare OS; alla riapertura controllare storico/marker, nessun replay media |
| Kill non richiesto con causa affidabile | Riconciliare opt-in/bond/trigger, senza replay pending/duck/pause |
| Causa indeterminata | Riattiva AirPods esplicito richiesto |
| Riattiva da azione utente | Riconoscere gli eventi noti, avanzare consenso/generazione e cancellare suppress nello stesso gate, poi nuova osservazione/bootstrap |

Il consumo di un evento non cancella suppress. Dopo Riattiva lo stesso vecchio
evento non risopprime; uno nuovo sì. Query/persistenza fallite non aprono socket.
Riconciliare una volta per process epoch e azione rilevante, senza polling/retry.
Fonte: [stop utente FGS](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping),
verificata 2026-11-08: Task Manager termina l'app senza callback, ExitInfo è il
riscontro al ritorno. Consumo/suppress sono la policy Fluidify, non automatiche Android.
Test: USER_REQUESTED→tre ingressi concorrenti→zero socket; stesso evento dopo
Riattiva; nuovo stop; history vuoto/errore+marker; stop interno, force-stop, kill
ordinario e semplice apertura UI distinti. Nessun PLAY nel cleanup.

## C5 — Ear pause/resume e uso mono

Stati normalizzati da RX valido: B=entrambi, L/R=solo quel lato, N=nessuno,
U=almeno un lato unknown/assente o campione non validabile. Non ricavare N da
batteria assente o raw non riconosciuto. Il decoder valida il layout; la policy
non interpreta byte wire. Primo RX/riabilitazione/cambio coppia sono baseline
senza PAUSE/PLAY o token posseduto. Duplicati non ripetono effetti.

E=motivo pausa ear. R=rilascia E e considera PLAY soltanto con pausa confermata
posseduta da Fluidify, stesso target/session/route/epoch, nessun override osservabile
o altro motivo CA/chiamata e opzione ripresa attiva. Altrimenti rilascia senza PLAY.
Non copiare l'autoplay forzato del ramo LP N→mono quando non si possiede la pausa.

| Prima | Dopo | Effetto ear | Ripresa |
|---|---|---|---|
| B | L/R | Acquisire E; PAUSE solo target pertinente osservato PLAYING; token solo dopo PAUSED osservato | No |
| B | N | Come sopra; disconnect ordinario solo se opzione/capacità disponibili | No |
| L/R | N | Acquisire/mantenere E; se mono manualmente PLAYING, PAUSE osservabile | No |
| N | L/R | R; uso mono ammesso | Solo condizioni R |
| N | B | R | Solo condizioni R |
| L/R | B | R | Solo condizioni R |
| L | R | Conteggio mono invariato; mantenere E se presente; nessun nuovo PAUSE/PLAY | No |
| R | L | Conteggio mono invariato; mantenere E se presente; nessun nuovo PAUSE/PLAY | No |
| Valido | Uguale | Nessun effetto/rinnovo token | No |
| Qualunque | U | Nessun comando; invalidare auto-resume ear e richiedere nuova baseline valida | No |
| U/primo RX | B/L/R/N | Baseline senza effetti | No |

Manual PLAY con E attivo invalida token e rilascia E; non ripausare su duplicato.
Una nuova transizione verso N può nuovamente pausare. Un mono iniziato da N→L/R
o manualmente non si ferma per il solo fatto di essere mono; B→mono rappresenta
invece rimozione durante ascolto stereo. CA con pausa aggiunge solo il proprio
motivo, senza doppio PAUSE; fine CA non libera E, reinserimento non libera CA.
Solo fine ultimo motivo considera R. Il duck CA è indipendente dai motivi pausa.
Toggle ear OFF/Arresta/disconnect/revoca/cambio sessione invalidano i token e
rimuovono il motivo pertinente senza PLAY; riabilitare ristabilisce baseline.

La preferenza di ripresa ear riguarda soltanto il reinserimento. La pausa CA
include la ripresa della propria pausa al termine della conversazione, anche con
ear-resume OFF. In caso di sovrapposizione devono consentire la ripresa sia il
motivo che ha originato la PAUSE posseduta sia l'ultimo motivo rilasciato; nessun
PLAY finché rimane un motivo attivo. UNKNOWN/cache ear revocano l'autorità ear,
senza cancellare una pausa CA indipendente o attribuirle un'origine ear.

Il passaggio fra primo piano e background conserva baseline e autorità possedute
quando entrambi gli stati restano autorizzati dal consenso background e i fatti
del target sono invariati. La perdita effettiva di autorizzazione invalida gli
effetti; tornare visibili non riproduce quelli cancellati. Questo requisito vale
sia per il player locale sia per il ramo esterno C6.

Il PLAY emesso dall'automazione deve essere distinto dall'intento manuale anche
nei provider locali Librespot ed Exo. Uno stato PLAYING coincidente non basta:
la conferma deve essere riferita al comando proprio, allo stesso brano/target,
all'ordine di applicazione e alla generazione corrente. Soltanto tale conferma
può evitare che la ripresa propria revochi il ripristino del duck. Seek, altri
comandi, cambio brano/route/account o revoca invalidano comunque la ricevuta.
Un aggiornamento Connect `cluster`, da solo, non prova un intento manuale;
un vero trasferimento a un altro dispositivo resta un motivo di revoca.

Soltanto dopo il dispatch effettivo del PLAY proprio, una transizione del target
attraverso BUFFERING/CONNECTING può trattenere l'autorità già posseduta per al
massimo due secondi. Non crea ricevute, token o nuovi consensi. UNKNOWN prima
del dispatch, comando rifiutato, scadenza, ambiguità, revoca o intervento manuale
abbandonano gli effetti. Il confine dei renderer e il routing reali rimangono
parte del collaudo fisico: i callback sintetici non lo sostituiscono.

Test nominativi: B→L→B; B→R→N→L; N iniziale→L senza token; mono manuale L→R→B;
CA+E in entrambi gli ordini; reinserimento con CA; U; duplicati; manual PLAY/PAUSE,
cambio target e toggle OFF mentre in pausa. Verificare comandi/motivi/token.
Le tre sequenze fisiche ear includono stereo-removal, mono-reinsertion e overlap
CA con questi esiti attesi; assenza di hardware resta NV.

## C6 — Altre app, best effort esplicitamente scelto dall'utente

Il2026-11-08 l'utente ha scelto «Best effort come LibrePods» dopo la descrizione
dei limiti di route per-app e PAUSE manuale già PAUSED. Questa scelta sostituisce
il requisito di prova cooperativa stretta per le sole automazioni esterne.

- OFF predefinito. otherAppsEnabled è il consenso dedicato best effort, presentato
  chiaramente nelle impostazioni; resta distinto da automationsEnabled e background.
- Richiedere permesso NLS effettivo, una sola sessione locale PLAYING identificata,
  coppia selezionata realmente connessa e streaming A2DP coerente, assenza di
  chiamate/comunicazioni e ambiguità. Il routing è inferito: rappresentarlo con
  provenienza distinta, senza spacciarlo per AudioRouting del renderer esterno.
- Bloccare/revocare su target concorrenti/remoti, cambi sessione/device/route,
  perdita dei permessi, override osservabile, stop o cambio consenso. Ogni comando
  accodato ricontrolla i fatti, la generazione e l'epoch. Nessun trasferimento Connect.
- Distinguere ricevuta corroborata locale e correlazione esterna dopo richiesta
  propria. PAUSED/volume successivi e pertinenti possono attribuire ownership best
  effort soltanto sotto C6 e col consenso corrente. Non chiamarli ACK hardware o
  prova causale; non indebolire la policy dei target locali per accettarli.
- Una PAUSE manuale già PAUSED o una modifica non osservabile può restare ignota;
  dichiararlo nel consenso. Applicare comunque tutti gli override osservabili e
  non riprodurre automaticamente un target nuovo o ambiguo.
- CA esterna usa un solo arbitro STREAM_MUSIC del telefono e una sola baseline.
  Altro audio può condividere il mix. Volume divergente/manuale, duck concorrente o
  cambi di dominio fanno rinunciare al ripristino. Cleanup non emette PLAY/rollback.
- Dopo una pausa propria la prova di streaming può essere trattenuta come
  evidenza storica limitata allo stesso target/epoch/route: invalidarla su ogni
  evento contrario e dichiararla inferenza, mai route corrente misurata.
- Una sessione concorrente soltanto PAUSED non rende da sola ambiguo il target
  posseduto. Ripresa e restore restano legati a quello stesso target; sessioni
  attive concorrenti, remote o indeterminate conservano i blocchi pertinenti.
- La finestra transitoria dopo il PLAY proprio conserva soltanto la correlazione
  C6 già valida, con la sua provenienza best effort. STOPPED, ERROR, NONE, skip,
  seek o una nuova PAUSED revocano anche dentro la finestra. Nessuna correlazione
  esterna diventa una ricevuta causale del renderer locale.

Test: opt-in OFF/ON/revoca, controller singleton e concorrenti, Connect remoto,
altro dispositivo o BT non streaming, cambio route durante PAUSED, callback
ritardata/stale, override manuali osservabili, CA/ear overlap, una sola baseline e
assenza di secondo duck, nessuna ripresa/restore nel cleanup. Gate fisico conserva
tre sessioni standalone, prove con altre app/background e casi di audio separato;
una fixture o un test host non diventa PASS hardware. G1 rimane NV storico.
