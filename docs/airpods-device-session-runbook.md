# AirPods — sequenza operativa per il collaudo sul telefono

**Non eseguita. H1/G1 NV; conformance prodotto FALSE.** Questa sequenza traduce
il gate già definito in `airpods-standalone-validation.md`; non ne riduce requisiti
o soglie. Non prova che un dispositivo o le cuffie siano pronti adesso.

## Ingresso dopo la stabilizzazione software

1. Usare il candidato congelato che ha superato le review pertinenti. Registrare
   manifest e SHA dell'APK debug, firma/versione installata, build effettiva del
   telefono e identità/firmware AirPods. Preservare dati e account Fluidify.
2. Il telefono assegnato è SM-S931B, host `local`, ID `RFCY61FZDHW`. Il tablet
   `R52X50DKRBW` appartiene a FluidWeather. Aprire il solo telefono con gli strumenti
   Device; per azioni e screenshot usare il comando agent-device restituito dalla
   sessione propria. Nessun riuso o chiusura di pannelli altrui.
3. Chiedere la disponibilità attuale delle cuffie e la cooperazione per indossamento,
   ascolto e conversazioni. Le vecchie conferme non attestano lo stato odierno.
   Verificare una baseline esclusiva osservabile. Non terminare o riconfigurare
   LibrePods per fabbricarla. Se il suo canale resta attivo o non è identificabile,
   registrare il limite e lasciare NV le prove che dipendono dall'esclusività.
4. Avviare la raccolta log della sola finestra di prova, correlando UID/socket e
   timestamp. Conservare originale privato e riferimenti sanitizzati; nessuna
   pubblicazione di indirizzi personali, notifiche o credenziali estranee.

## Controlli del pannello debug realmente presente

L'ingresso è `AirPodsDiagnosticActivity`, sottoclasse debug non esportata di
`AirPodsFunctionalHarnessActivity`; non viene reso esportato per facilitarne
l'avvio. Usare soltanto un ingresso debug autorizzato e azioni visibili. Gli
extra/restore non scelgono coppia o consenso. Aprire la schermata non avvia il
trasporto e non concede l'abilitazione prodotto.

Su un'installazione senza login Spotify, il percorso già presente è **Salta** nel
tutorial → **Impostazioni** nella Home disconnessa → **Informazioni** → sezione
**Debug** → **Diagnostica AirPods (POC)**. Quest'ultima etichetta storica apre ora
la corsia funzionale condivisa, non un secondo owner POC. Il pulsante diretto
**Impostazioni AirPods** del tutorial apre invece le impostazioni ordinarie, non
il pannello di avvio TEST. Questo percorso è verificato nel codice di
`OnboardingScreen`, `HomeScreen`, `SquareApp` e `SettingsScreen`; la sua esecuzione
sul telefono resta da verificare. Non serve un account Spotify per raggiungerlo.

- **Permessi reali / elenca abbinati** richiede i permessi mancanti con dialoghi
  Android, quindi elenca i bond. Scegliere esplicitamente la coppia; non usare il
  solo nome Bluetooth come identità hardware.
- **Avvia TEST esplicito su questa coppia** crea un permit e uno store scratch.
  Attendere gli stadi effettivi: costruzione, connect, bootstrap e INFORMATION.
  `bootstrap/RX`, `media`, identità, proprietà osservate e pending sono evidenze
  diverse. Un timeout/errore va conservato; niente retry automatici o nuove firme
  di socket alla cieca.
- **Avvia raccolta diagnostica TEST** e **Snapshot diagnostico TEST (NV)** aiutano
  la correlazione. Uno snapshot visualizzato non sostituisce i log di RX, eventi
  ed effetti. Candidati da tabella modello restano distinti da supporto osservato.
- Per ogni proprietà prendere prima nota dello stato corrente. Gli input numerici,
  nome UTF-8, EQ e opzioni long press sono richieste tipizzate: registrare il relativo
  readback/effetto o il timeout. Ripristinare solo un valore iniziale conosciuto con
  un'azione esplicita e verificarne la ricezione. Non inventare baseline mancanti.
- **Consensi di TEST** parte da valori separati e OFF. Abilitare solo la prova in
  corso; automazioni, ear pause/ripresa, CA, altre app e background restano distinti.
  L'accesso NLS e i permessi chiamata richiedono il consenso Android visibile.
  Nessuna chiamata reale o assistente avviato come effetto collaterale della lettura.

## Sessioni ed effetti

Completare S1/S2/S3 come aperture indipendenti con chiusura e normale riconnessione.
Per ciascuna famiglia applicabile usare la matrice del gate, non il solo numero di
controlli disponibili. Unknown, diniego e timeout non significano NA. Una richiesta
audio/assistente/chiamata accettata non dimostra il risultato.

Per ear/CA raccogliere separatamente RX e testimonianza dell'utente. Eseguire le
sequenze C5, overlap nei due ordini, primo CA live e override. Ripetere il percorso
locale Spotify e file/video, poi il target esterno C6 con i consensi dedicati.
Annotare i limiti non osservabili di route e PAUSE già PAUSED senza certificarli.
Per nod/shake durante chiamata usare esclusivamente una chiamata di prova concordata.

## Background, chiusura e passaggio al prodotto

Il pannello espone **Arma background TEST 10 min** e **15 min**. Prima di uscire
abilitare il consenso background e armare esplicitamente una finestra valida.
Senza finestra, l'uscita dalla UI chiude il test. Non aggiungere wake lock per
far superare la prova. Effettuare il periodo concordato a schermo spento, altra
app attiva e pausa oltre 90 secondi; dopo il silenzio servono eventi nuovi.

Al ritorno usare **Arresta TEST**, osservare la chiusura reale e due minuti senza
riapertura. Il permit scaduto non prova da solo un close puntuale durante deep
sleep. Stop interno, revoca, unpair, Task Manager e force-stop hanno esiti diversi:
seguire la matrice concordata, non sostituire uno scenario con un altro.

Registrare la sessione nel ledger con tempi, artifact identity, origine di ogni
evidenza (log/osservazione utente/deduzione), risultato e limite. Il permit scratch
non viene migrato nel prodotto. Soltanto dopo il gate pertinente si potrà abilitare
il percorso di produzione, costruire/verificare il nuovo APK abilitato e provare
Home/player/quick/impostazioni nel vero host. Il checkpoint minificato con gate
FALSE non certifica quell'APK futuro né la resa ottica Samsung.
