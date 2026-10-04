# 1.5.3: apertura di Fluidify dalla voce media di sistema

La modalità Automatica evita correttamente una seconda voce nella Now bar. Mancava però nel manifest Wear l'ingresso che permette al sistema di aprire i controlli della app companion anziché il proprio lettore generico.

Il manifest di MainActivity dichiara ora `com.google.android.wearable.action.MEDIA_CONTROLS` con categoria DEFAULT. PlayerIntents lo tratta come una richiesta del lettore; la navigazione già esistente gestisce sia il primo avvio sia onNewIntent quando l'app è rimasta su un'altra pagina. L'azione non trasferisce l'audio e non aggiunge sessioni o notifiche. I PendingIntent della riproduzione autonoma restano quelli del lettore locale.

La selezione è stata verificata sul pacchetto di sistema dell'immagine ufficiale SDK Wear OS 6.0, API 36, revisione 1: `com.google.android.wearable.media.sessions`, versione `1.8.86.745532156` (100092531). L'implementazione cerca questa azione nel package della app musicale; per i controlli del telefono verifica anche l'identità tramite la firma. Senza una activity risolvibile usa i controlli di sistema. Fluidify telefono e Watch condividono package e firma.

Due regressioni verificano la risoluzione dell'azione implicita nel manifest, la destinazione esportata e la richiesta del lettore per tocco e auto-launch. I sette test della politica Now bar verificano che Automatica continui a evitare il duplicato.

Verifiche eseguite: 9/9 test passati; build minificate di telefono e Watch 1.5.3 (11), stessa firma; manifest dell'APK Wear finale con l'azione media; lint Wear mirato alle API e ai componenti senza problemi. Sull'emulatore Wear OS 6 l'azione implicita risolve MainActivity, apre il lettore a processo chiuso (auto-launch) e torna dalla Coda al lettore tramite onNewIntent (tocco). Il dump UI contiene i controlli del lettore; nessun servizio di riproduzione locale avviato e nessun errore AndroidRuntime durante queste prove. Il telefono non è abbinato all'emulatore: lo stato mostrato è senza brano.

La prova completa del tocco nella Now bar Samsung richiede il Galaxy Watch collegato al telefono; non è equivalente all'apertura dell'ingresso sull'emulatore. Le prove da completare sono nella sezione 1.5.3 della checklist.
