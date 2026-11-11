**Esito: richiede correzioni software.** Ho individuato un difetto nel consenso al background e due problemi nelle impostazioni. H1 resta **NV**, senza penalizzazione per l’assenza del telefono; il gate prodotto deve rimanere FALSE.

Reviewer D indipendente: **GPT-6.1 Sol, reasoning high**. Fase **IMPLEMENTAZIONE**, giro **INIZIALE**, versione **I0 stabile**. Nessuna subdelega, modifica, build, installazione o operazione su dispositivi.

Snapshot esaminato: `build/review-airpods-full-implementation-I0-stable`. Manifest SHA256 corrispondente a `77cfdf2a2e9561a0a4b91802217e77b010a2bcb85c5b87dd8c24b5abf3b659bb`; verificati dimensioni e SHA256 di **1.130/1.130 file**. Anche l’input esterno corrisponde al manifest ed è stato osservato privatamente. Nessun AGENTS applicabile trovato nei percorsi controllati. Lette le skill richieste e le letture normative indicate; non consultati altri report/voti o il documento di stato vietato.

| Criterio | Voto | Stato verifica | Motivazione/evidenze | Problemi/gravità |
|---|---:|---|---|---|
| I1 — Completezza funzionale | NV | Ricognizione statica parziale | Esaminati copertura, registro delle 35 famiglie, proprietà, modelli e principali entry point UI/host. Verificata integrità delle 133 fonti pinned. Non ho completato la riconciliazione semantica individuale di tutte le 67 preferenze e 41 esclusioni: non attribuisco un voto di completezza. | Completezza integrale non certificata da questa review. |
| I2 — Home/player/quick/full settings | **7** | Statica approfondita; fixture parent osservate | Home usa il confine misurato prima di friends/avatar, centra la capsula nel residuo e la nasconde prima della sovrapposizione/collasso. Due target da 48 dp; quick condiviso con accesso completo; ingresso signed-out indipendente. Individuati problemi nel consenso modificabile e nelle spiegazioni setup. | **D2 media**, **D3 bassa**. Contrasto del completo host Settings e resa Samsung NV. |
| I3 — Lifecycle e ownership | **8** | Statica mirata e test parent esaminati | Startup inerte con conformance FALSE; gate persistente, admission prima delle acquisizioni, servizio non sticky, componenti protetti e Tile listening inerte. Esaminati corpi dei test su stop, storico, concorrenza e callback obsolete. | D2 interessa la revocabilità delle preferenze. Verifica completa dei rami CDM/OEM non eseguita personalmente. |
| I4 — Protocollo/stato/capability | **8** | Statica mirata; artefatti parent | Identità riconosciuta distinta dal nome Bluetooth; tabella modello separata dalle osservazioni; range/encoding tipizzati, pending e generazioni. Esaminati codec e vettori, inclusi chime, inversione Adaptive, PPE, EQ e i due formati tracking. Nessun mismatch modello concreto identificato nel campione controllato. | Ricezione e comportamento dei firmware reali NV. |
| I5 — Automazioni e controllo manuale | **6** | Difetto deterministico da sorgente | C6 conserva correlazioni esterne distinte dalle ricevute locali; i test esaminati controllano override, overlap, timeout e cleanup senza PLAY. Tuttavia il ramo esterno non applica l’interruttore background. | **D1 alta**: consenso dichiarato non rispettato. |
| I6 — Validazione e artefatti | **9** | Checker propri + evidenze parent | Ripetuti controlli class_defs, ELF e integrità. XML parent riconteggiati; letti corpi significativi dei test. Mapping conserva il nome JNI. Limiti degli artefatti dichiarati correttamente. | Firma/ZIP16KB derivano dai controlli parent archiviati. Nessuna prova completa di equivalenza sorgente/binario o runtime. |
| I7 — Confini/permessi/provenienza | **8** | Statica mirata | Fonti pinned verificate anche rispetto ai Git blob; attribuzione GPL presente, icona della capsula originale, percorsi ordinari per permessi espliciti. Engine **3.0.5**, app **2.0.1 (24)** mantenuti. Nessuna nuova espansione hidden API identificata nel perimetro letto. | Audit esaustivo di tutte le esclusioni non completato; vedi I1. |
| I8 — Integrazione/preservazione | **8** | Statica su wiring e seam pertinenti | Binding comune in SquareApp, host quick unico, accesso onboarding e Settings, bridge media installato dall’Application. Connessione audio distinta da AACP; richiesta accettata distinta dall’effetto confermato. | Continuità del decoder reale NV. Snapshot parziale: preservazione dell’intero lavoro estraneo non certificabile. |
| H1 — Gate fisico standalone | **NV** | Non eseguito | Mancano nuove evidenze fisiche autorizzate delle tre sessioni e delle famiglie applicabili. Fixture, invii accettati e build non soddisfano H1. | Gate obbligatorio ancora aperto; G1 storico resta NV. |

**D1 — Alta: automazioni esterne operative con background OFF.**

Trigger: sessione prodotto ammessa, `automationsEnabled=true`, `otherAppsEnabled=true`, `fluidifyBackgroundEnabled=false`, unica sessione esterna eleggibile; Fluidify passa in background e arrivano nuovi eventi ear/CA.

In [MediaRuntimeCore.kt:109](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/media/MediaRuntimeCore.kt:109>) il controllo `visible || settings.fluidifyBackgroundEnabled` si applica soltanto al target locale. Il ramo esterno verifica automazioni, consenso altre app ed evidenza C6. `visible(false)` invalida gli effetti precedenti ma riconfigura il coordinatore; nuovi effetti possono superare anche il controllo finale di `submit`, righe 175–180.

Conseguenza: possibili PAUSE/attenuazioni esterne mentre l’interruttore «Automazioni con Fluidify in background» è OFF. Il consenso C6 visualizzato dichiara espressamente un interruttore background separato. È una conclusione statica sul percorso raggiungibile, **non una prova di effetto fisico**; l’attuale gate FALSE ne impedisce l’uso prodotto.

Fix minimo: applicare il requisito visibilità/background anche al ramo esterno, mantenendo separati consenso C6 e ricevute locali. Regressione: matrice target locale/esterno × visibile/background × switch ON/OFF, con nuovi eventi e comandi accodati. Il test `backgroundAndOtherAppsHaveSeparateOptIns`, in `MediaRuntimeCoreTest.kt:119`, non copre esterno C6 ON con background OFF.

**D2 — Media: il consenso già ON non può essere disattivato quando manca un prerequisito di attivazione.**

Trigger: `otherAppsEnabled=true`, poi revoca NLS oppure disconnessione con `runtime.mediaAvailable=false`; riapertura delle impostazioni.

[AirPodsHost.kt:120](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/ui/airpods/host/AirPodsHost.kt:120>) usa `configured && nls && runtime.mediaAvailable` come disponibilità unica del toggle OTHER_APPS. La UI disabilita entrambe le direzioni e il dispatcher, riga 163, respinge anche `Toggle(..., false)`. Analogamente, il gate comune `configured` può impedire lo spegnimento delle preferenze dopo revoca Bluetooth.

Conseguenza: l’utente non può revocare selettivamente la preferenza salvata senza ripristinare requisiti o ricorrere ad Arresta/Dimentica. **Non implica che gli effetti continuino senza NLS**: i controlli media restano presenti.

Fix minimo: separare autorizzazione ON da revoca OFF; permettere lo spegnimento della preferenza della coppia selezionata anche senza media adapter/permessi operativi. Regressione sul vero dispatcher: consenso ON → NLS revocato o media assente → OFF persistito, senza acquisizioni né PLAY.

**D3 — Bassa: azioni setup abilitate mostrano una spiegazione di indisponibilità.**

In [AirPodsControls.kt:372](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/ui/airpods/AirPodsControls.kt:372>) `SetupButton` mostra `reason ?: airpods_ui_unavailable`. Il produttore imposta `reason=null` quando l’azione è abilitata. Pertanto azioni disponibili come Bluetooth Settings, esportazione o cancellazione diagnostica mostrano «Collega gli AirPods per usare questa impostazione».

Fix minimo: sottotitolo neutro/vuoto quando abilitata; motivo concreto quando disabilitata. Regressione UI usando disponibilità prodotte dall’host reale.

**Verifiche e limiti delle evidenze.**

Ho eseguito personalmente:

- Integrità dello snapshot e dell’input privato; SHA256, dimensioni e Git blob delle **133 fonti pinned**, tutti corrispondenti.
- Checker congelato delle definizioni DEX debug: **296 AirPods, 67 UI, 10 bridge, 27 auth**, tutti presenti.
- Controllo indipendente ELF64 LOAD: **14 librerie debug, 7 minified, 6 Wear**, nessuna violazione dell’allineamento verificato a 16 KB.
- Presenza della classe JNI nel class_defs minified e corrispondenza del nome nel mapping.
- Riconteggio XML: **555 app** e **168 core/Wear**, zero failure/error/skip. Sono esecuzioni **del parent**, non mie.

Ho letto corpi dei test su geometria Home, due target, signed-out/back, callback del dispatcher, ownership locale, override e vettori wire. Le fixture osservate sostengono geometria sintetica e finitezza del quick, incluso font 2; non dimostrano ottica Samsung, contrasto dell’intero host Settings o raggiungibilità dell’ultimo controllo nel completo host produttivo.

Build debug/R8/Wear e verifiche firma/ZIP16KB sono evidenze parent archiviate. **R8 con conformance FALSE può eliminare percorsi gated**: il risultato non certifica le automazioni dopo una futura abilitazione. Nessun lint completo, continuità decoder reale, esito telefonico o comportamento hardware è assunto PASS.

La review richiede la correzione di **D1–D3** e relative regressioni software. Non autorizza l’abilitazione prodotto né chiude H1.
