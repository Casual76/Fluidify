# Fluidify 1.6.0 — effetti musicali e verifiche

Gli effetti sono abilitati per impostazione iniziale, con due preferenze indipendenti conservate in `audio_light`. La preferenza del telefono include il mini player; quella Watch controlla sia la riproduzione autonoma sia la sottoscrizione al telefono. Le preferenze del vetro conservano il loro significato.

## Implementazione

- `core/playback/AudioReactive.kt`: tap PCM di sola lettura, quattro buffer mono riutilizzati, coda limitata, worker dedicato. FFT 2048 con Hann e otto bande logaritmiche sul telefono, al massimo 30 analisi/s; RMS e passa-basso sul Watch, al massimo 10/s. Nessuna attesa di analisi, rete o UI nel produttore PCM. Soglia di silenzio e pavimento della normalizzazione evitano di amplificare artificialmente i passaggi tranquilli.
- Collegamento all'uscita nativa e al termine della catena Media3 già usata per file locali/video. I byte di uscita, la velocità e gli effetti audio esistenti non vengono alterati dal tap; il Canvas decorativo non alimenta l'analisi. Le misure considerano l'audio accodato e vengono invalidate su seek, traccia, velocità, pausa e sorgente.
- Lampade radiali senza bordo: una texture trasparente di 128×128 condivisa e immutabile, geometrie e pennelli riutilizzati. Nessuna sfocatura aggiuntiva. La luce del mini player è registrata nel suo backdrop, dietro lo stesso vetro. Variante compatta con altezza e intensità ridotte. Copertina e luce inferiore sono un'unica geometria interpolata sulla transizione Canvas; un Canvas non pronto mantiene la luce della copertina.
- Lettura dello stato nella fase di disegno: circa 60 aggiornamenti/s sul telefono e 30 sul mini player e sul Watch. Sul Watch sweep e varco dell'ora restano legati alla posizione musicale; cambiano soltanto luminosità e diffusione verso l'interno. Tile e complicazioni conservano il rendering esistente.
- Capability opzionale `audio-light`; sottoscrizione rinnovata ogni due secondi, scadenza dopo cinque. Solo nodo `isNearby`, MessageClient, aggregati al massimo cinque volte/s. Identificatore, sequenza, generazione, traccia, posizione e freschezza sono verificati. Dopo un secondo senza frame validi resta il normale avanzamento.
- Il listener dei frame Watch esiste soltanto nella UI visibile; il percorso `/visual-fluidify/1/frame` resta fuori dal filtro del servizio persistente. Nessun PCM, DataItem, wake lock, richiesta Wi-Fi o avvio del motore per la luce. Uscendo da lettore/copertina, entrando in AOD/background o disabilitando l'effetto, il listener è rimosso e la sottoscrizione annullata; la scadenza limita anche il caso di processo terminato.

## Evidenze automatiche

Comando delle suite: `:core:testDebugUnitTest :wear-protocol:test :wear:testDevUnitTest :app:testDevUnitTest :engine-wear:testDebugUnitTest`.

Build finali `:app:assembleRelease :wear:assembleRelease` minificate con R8; versione 1.6.0 (12) e medesimo certificato di release su entrambi gli APK. Lint mirato alle API e ai componenti: zero errori; nessun avviso Wear, cinque avvisi preesistenti del telefono sulle costanti di `ConnectRouteProvider`. Non è una dichiarazione di lint completo senza avvisi.

184 test nelle cinque suite (7 core, 61 protocollo, 72 Wear, 18 app, 26 engine-wear), senza errori. Copertura nuova: toni sintetici su otto bande e tre frequenze di campionamento; impulso basso/silenzio; finestre suddivise; PCM16/float, mono/stereo/sei canali; byte invariati; audio accodato e velocità; cambio sorgente/seek; scadenza/rinnovo/disconnessione/vecchia capability; frame vecchi, duplicati, fuori ordine, altra traccia o posizione; preferenze, schermo, risparmio energetico e scala animatori; dissolvenza di 250 ms anche con frame saltati.

Render Roborazzi delle schermate reali in `app/screenshots` e `wear/screenshots`: telefono chiaro/scuro, tablet, luce inferiore, Canvas pronto/in caricamento, mini player normale/compatto/translucido e confronto effetti disattivati; Watch 192/216/240 dp, silenzio, bassi e copertina immersiva. Il test dei mini player confronta anche i pixel dopo la rimozione del segnale: la luce deve apparire e sparire sotto il vetro.

Misura ripetibile `AudioLightBudgetTest`, 1000 iterazioni dopo riscaldamento, JBR Windows/Robolectric, Canvas software nativo: circa 0,30 ms CPU per FFT, 0,016 ms per analisi leggera; circa 12 ms per il disegno telefono acceso, 0 ms spento, 3,7 ms mini player, 0,30 ms anello. Il JSON preciso è generato in `app/build/audio-light-host-performance.json`. Queste sono misure del PC con risoluzione del timer limitata, **non** prestazioni GPU, frame time o batteria di telefono e Watch. Non dimostrano i consumi sul Galaxy Watch.

## Now bar

L'utente ha confermato che il problema persiste con **1.5.3 su entrambi i dispositivi**. Questa versione aggiunge `com.google.wear.services.media.action.REMOTE_MEDIA_ACTIVITY`, oltre al precedente `com.google.android.wearable.action.MEDIA_CONTROLS`; entrambe le azioni aprono il lettore Fluidify e gestiscono anche l'activity già aperta. Il nuovo contratto è documentato da [MediaManager](https://developer.android.com/reference/com/google/wear/services/media/MediaManager), introdotto pubblicamente nell'API 37. La presenza o l'uso su un firmware Wear OS 6 Samsung dipendono dall'implementazione del sistema: **non considerare risolto il tocco Samsung in base al solo test degli intent**.

APK di release firmato installato nell'emulatore temporaneo Wear OS 6: risoluzione di entrambe le azioni verso MainActivity, aperture a processo chiuso, ritorno dalla Coda tramite il nuovo intento. Dump UI con Previous/Play/Next/Audio output/Queue; nessun errore AndroidRuntime e nessun servizio di riproduzione locale avviato. L'emulatore non è abbinato al telefono e non riproduce il firmware Samsung.

## Prove fisiche ancora da completare

La checklist 1.6.0 in `wear-qa-checklist.md` conserva le prove da fare su telefono/tablet e Galaxy Watch collegati: sincronizzazione reale con Spotify/file/video, transizione Canvas, pausa/buffering, prestazioni CPU/GPU, consumi, traffico Bluetooth e assenza di traffico dopo AOD/background. Nessun dispositivo fisico è disponibile via ADB in questa sessione. La risoluzione di un intent sull'emulatore non equivale a toccare la Now bar Samsung.

Fluid Engine resta alla versione 2.11.0; nessuna modifica al submodule.

## Correzione visiva 1.6.1

In seguito alla prova dell'utente, l'arco Watch non è più un contorno luminoso stretto: un campo luminoso combina dissolvenza radiale e angolare, così anche le estremità sfumano. La diffusione verso l'interno è più ampia; il varco dell'ora resta vuoto e la dissolvenza finale termina nella posizione del brano. Shader e geometria sono riutilizzati; il disegno resta confinato alla parte utile dell'arco, evitando di elaborare il centro trasparente o la parte non raggiunta. Nessun blur, analisi o traffico aggiuntivo.

Sul telefono il colore della copertina viene trasformato in luce conservandone la tonalità ma alzandone la luminanza minima. Maggiore espansione e intensità nel lettore, maggiore altezza e intensità nel mini player dietro il vetro; la variante compatta resta attenuata. Nessuna modifica alle preferenze del vetro.

Aggiornati e controllati i 17 render, inclusi tema chiaro/scuro, Canvas, mini player compatto e Watch 192/216/240 dp. Il controllo di visibilità del mini player richiede ora una differenza superiore a 20 nel canale rosso tra segnale presente e assente, rispetto alla soglia di 5 della 1.6.0. La resa animata e i consumi sui dispositivi fisici restano da verificare; i render statici non dimostrano questi risultati.

Il benchmark host aggiornato misura circa 19,36 ms CPU per disegno telefono, 5,91 ms mini player e 1,02 ms anello Watch. Il primo prototipo Watch che disegnava l'intero disco misurava 4,47 ms: confinare la stessa luce alla geometria utile elimina gran parte di quel lavoro. L'analisi resta circa 0,25 ms FFT / 0,016 ms leggera. Dati JBR/Robolectric con Canvas software, non GPU Android né misure di consumo sul Watch; il disegno più ampio richiede comunque la verifica fisica.

## Correzione visiva 1.6.2

Ridotta la sola estensione verticale della luce telefono: 35% nel lettore completo, anche durante la transizione da copertina a luce inferiore, e 30% nel mini player normale/compatto. Intensità, colori, tempi della risposta musicale e dimensioni orizzontali conservati. Il disegno dell'anello Watch non cambia.

Passati gli 11 render telefono/tablet, Canvas/video e mini player, compreso il controllo di visibilità attraverso il vetro. La resa in movimento e i consumi reali restano da verificare sul dispositivo; le prove automatiche non sostituiscono questa valutazione.

Benchmark host aggiornato: circa 13,20 ms per disegno telefono e 4,31 ms mini player, rispetto a 19,36 / 5,91 ms della 1.6.1; il campo luminoso più basso riduce l'area da disegnare. Restano misure del Canvas software su PC, non prestazioni o batteria Android.
