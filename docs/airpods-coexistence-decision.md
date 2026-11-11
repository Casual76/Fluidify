# AirPods: decisione alla scadenza delle otto ore

Finestra autorizzata: **2026-11-07 19:52:42 UTC – 2026-11-08 03:52:42 UTC**
(20:52:42 – 04:52:42 Europe/Rome). Consolidamento del 2026-11-08, successivo
alla scadenza; registro di chiusura acquisito alle **03:59:13 UTC**.
Nessuna proroga, nuova ricerca o nuovo timer.

**Esito: nessuna soluzione completa verificata per Fluidify insieme a LibrePods
ufficiale invariata e attiva anche in background. Il gate G1/CP1.b rimane NV.**
Il connect concorrente eseguito ha un esito negativo dimostrato; non soddisfa da
solo la definizione di FAIL complessivo del piano, né dimostra un'impossibilità
universale. La ricerca a tempo è conclusa; il prodotto AirPods non è completato.

## Evidenze decisive e loro portata

- **Prova diretta singola:** Fluidify ha ricevuto modello e batterie dalle AirPods.
  LibrePods non aveva in quel momento un canale contemporaneo stabile. Questo
  dimostra il trasporto diretto in quella prova, non la convivenza né tutte le funzioni.
  Riferimento: [POC S25](airpods-poc-s25.md).
- **Tentativo concorrente G1:** il 7 novembre alle 19:57:11.419, socket Fluidify83,
  fallimento locale `no RCB available` prima di handshake/notifiche/comandi AACP;
  cleanup immediato. Nove transazioni modalità della baseline LibrePods avevano RX
  entro5s; ricezioni successive e audio percepito regolare non dimostrano due reader.
  Gli eventi concomitanti del server UID1002 sono distinti da LibrePods: il loro
  impatto funzionale non è misurato. Mancano tre sessioni complete, CA bidirezionale,
  conversazioni/gesti e lifecycle. Riferimenti: [G1](airpods-worn-gate.md) e
  [due review G1](airpods-gate-review-G1.md).
- **Registrazione Samsung:** il binario esaminato ha15 RCB classici. La prova host
  preesistente su allocatore/finder copre32768 occupazioni sintetiche; quattro
  scenari Register mostrano che due registrazioni outgoing sono consentite con due
  posti liberi. Non misura gli occupanti runtime, GAP/JV, radio, fanout AACP o durata.
  Non è emersa una leva deterministica della sola app per liberare/riusare risorse
  senza alterare LibrePods o la piattaforma. La risorsa BIP/AVRCP non dipende dalle
  cover Fluidify nei sorgenti AOSP esaminati; il Java Samsung esatto resta indisponibile.
- **Percorsi alternativi:** stock LibrePods offre alcuni comandi, ma non un contratto
  remoto completo di stato ricevuto; GATT gestito è un percorso distinto e non è
  escluso dal noRCB, ma manca un endpoint AirPods verificato con mapping/RX di modalità
  e CA configurata. BLE, HFP, AVRCP, metadata Samsung, RFCOMM e FastConnect non hanno
  fornito la catena completa endpoint→apertura→bootstrap→TX/RX→freschezza→permessi→background.
  Le esclusioni rimangono riferite alle fonti e revisioni esaminate, non a ogni firmware.

Matrice e condizioni per riaprire le piste: [evidenze C01–C13](airpods-coexistence-evidence.md).
La mancanza di un nuovo contratto pubblico o dell'artefatto OEM esatto è un limite
concreto della ricerca; otto ore trascorse non sono una prova tecnica di impossibilità.

## Integrità, review e limiti delle verifiche

Congelato finale di ricerca: `build/review-C4-coexistence/manifest.json`, SHA256
`8a1fba179e026e1e204701f3bc936754e86ae09186c2b35ff74f51bcba17c304`.
Alla chiusura root ha ricalcolato dimensioni e SHA256: **771/771 file e7/7 input
esterni corrispondenti**, nessun mismatch. È una verifica di integrità locale,
non una nuova esecuzione degli audit, una certificazione delle fonti OEM o una prova radio.

Due reviewer indipendenti GPT-6.1 Sol high hanno concluso C4 senza rilievi correttivi
confermati e con tutti i voti numerici almeno8. Tabelle individuali, aggregati e
distinzioni fra controlli propri/storici sono in [review C4](airpods-coexistence-review-C4.md).
C4 è il **terzo giro correttivo dopo l'iniziale** della ricerca: nessun reset o C5.
Questa chiusura amministrativa non aggiunge una nuova approvazione tecnica.

L'[appendice root successiva a C4](../build/airpods-8h/public-attachment-audit/REPORT.md)
non è coperta dalle sue review. Due raccolte pubbliche advertising Pro2/Pro3 e altri
allegati non hanno fornito un contratto completo mode/CA; non diventano un PASS
né una prova sul telefono dell'utente. La delega GAP/JV terminata failed per filtro
del provider non ha prodotto prove riuscite ed è rimasta interrotta, senza aggiramenti.

Il software e l'APK I3 conservano l'approvazione storica nel loro perimetro;
la ricerca non ha aggiunto UI AirPods, reconnect A2DP o lifecycle di prodotto.
I test software precedenti non sono stati rieseguiti in questa chiusura e non
sostituiscono i collaudi hardware mancanti. Nessuna build, installazione o release.
I congelati I3/G1/C1–C4 non sono stati riscritti.

## Arresto delle attività e risorse

Root ha eseguito `task_cancel` sulle **25 deleghe** della propria ricerca/review:
**24 completed,1 failed,0 errori**. Tutte erano già terminali; i risultati sono
preservati e non restano loro turni di ricerca da attendere. Non sono stati avviati
agenti sostitutivi. Ledger: [deadline-cleanup.json](../build/airpods-8h/closure/deadline-cleanup.json).

Il pannello S25 risultava già rilasciato prima della finestra e nessun dispositivo
è stato acquisito durante la ricerca. Alla chiusura non è stato riaperto, interrogato
o spento alcun telefono; nessuna sessione altrui è stata chiusa. Non sono state
acquisite risorse build o sessioni exec persistenti per questa finestra, quindi
non è stato fermato alcun processo globale Gradle/JVM o build di un'altra chat.
Il timer unico di scadenza ha attivato questa consegna; non sono stati creati altri timer.

## Decisione successiva: controlli esclusivi, ancora da implementare

Si applica la preferenza già espressa dall'utente: **proseguire verso una sola app
alla volta come proprietaria dei controlli AirPods**, mantenendo LibrePods ufficiale
installata e invariata. Fluidify non prometterà il controllo simultaneo mentre
LibrePods mantiene la propria sessione. Non arresterà LibrePods, non ne cambierà
impostazioni o politiche e non tenterà un takeover. L'eventuale gestione manuale
della sessione resta all'utente; chiudere la sola interfaccia LibrePods non prova
che il servizio o il canale siano terminati.

Questa è la scelta del percorso successivo, **non un'implementazione già fatta o
collaudata**. Il passo concreto è un emendamento al piano P1 prima dell'integrazione:

1. Definire il gate esclusivo e sottoporlo alle due review previste, mantenendo G1/CP1.b
   come risultato storico NV. L'emendamento cambia il requisito di simultaneità,
   non cancella gli altri criteri di correttezza o trasforma G1 in PASS.
2. Collaudare il client singolo in tre sessioni con baseline e versioni registrate:
   modello/capacità, batterie nullable con timestamp, modalità e CA ON/OFF confermate
   da RX pertinenti entro5s, gesti/audio e interventi manuali volume/pausa. Nessuna
   attenuazione o ripresa automatica CA aggiunta da Fluidify. Nessuna contesa forzata
   con LibrePods, retry ciclico o falsa rilevazione della sua inattività.
3. Verificare separatamente session owner, pausa90s, rilascio entro2s quando richiesto,
   revoca/unpair/logout/disattivazione, eventi vecchi scartati e due minuti senza
   riaperture autonome. Conservare timeout10s e pending osservativi5s. Solo il nuovo
   gate approvato e superato consentirà UI/prodotto; A2DP richiede il proprio collaudo.

Finché il telefono non è nuovamente disponibile per autorizzazione dell'utente,
le prove fisiche rimangono NV. Non viene richiesto di liberarlo ora. Nessun widget,
fork, root, patch di sistema, modifica engine o impostazione LibrePods è introdotto.

La chiusura del goal riguarda la **ricerca a tempo e questa consegna**. Implementazione
esclusiva e suoi gate costituiscono lavoro successivo aperto; il piano AirPods nel
suo insieme non viene dichiarato completato.
Il goal di ricerca è ora `complete`. Il contatore restituito riporta8851s
(2h27m31s) di attività registrata; la finestra calendario è di otto ore, comprendendo
il periodo in cui il goal era bloccato per mancanza dei dati discriminanti. Non si
presentano queste otto ore come attività continua o come prove hardware.
